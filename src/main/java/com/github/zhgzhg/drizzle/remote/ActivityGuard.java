package com.github.zhgzhg.drizzle.remote;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Keeps remote commands from overlapping with each other and with Drizzle actions started inside the IDE
 * (the Tools &rarr; Drizzle menus, Verify and Upload).
 * <p>
 * Arduino IDE loads every tool in a separate class loader per editor window, so static fields aren't shared
 * between windows. The state therefore lives in system properties (JVM-wide, String values only), guarded by an
 * interned string literal, which is the same object in every class loader.
 */
public final class ActivityGuard {
    private ActivityGuard() { }

    private static final Object MONITOR = "com.github.zhgzhg.drizzle.remote.ActivityGuard.MONITOR";
    private static final String REMOTE_PROPERTY = "drizzle.activity.remote"; // "<thread id>|<lease id>|<description>"
    private static final String LOCAL_PROPERTY = "drizzle.activity.local"; // a "<lease id>|<description>" line per activity

    /**
     * Releases the activity. Closing more than once has no effect.
     */
    public interface Lease extends AutoCloseable {
        /**
         * @return false once the lease was closed, or cancelled through {@link #cancelRemote()}
         */
        boolean isHeld();

        @Override
        void close();
    }

    /**
     * Starts an exclusive remote activity on the current thread.
     *
     * @return the lease, or null when another remote command or an IDE action is running
     */
    public static Lease tryRemote(final String description) {
        String value = Thread.currentThread().getId() + "|" + newLeaseId() + "|" + singleLine(description);
        synchronized (MONITOR) {
            if (System.getProperty(REMOTE_PROPERTY) != null || !localEntries().isEmpty()) {
                return null;
            }
            System.setProperty(REMOTE_PROPERTY, value);
        }
        return new RemoteLease(value);
    }

    /**
     * Starts an activity triggered from the IDE. Several can run at once, but not while a remote command runs,
     * unless it's the remote command's own thread (e.g. a remote compile going through the Verify hook).
     *
     * @return the lease, or null when a remote command is running
     */
    public static Lease tryLocal(final String description) {
        synchronized (MONITOR) {
            String remote = System.getProperty(REMOTE_PROPERTY);
            if (remote != null) {
                return remote.startsWith(Thread.currentThread().getId() + "|") ? new NestedLease() : null;
            }

            String entry = newLeaseId() + "|" + singleLine(description);
            List<String> entries = localEntries();
            entries.add(entry);
            setLocalEntries(entries);
            return new LocalLease(entry);
        }
    }

    /**
     * Ends the running remote command's hold, so the IDE becomes usable again, and interrupts its thread. A command
     * stuck in a blocking operation (e.g. a download) may keep running in the background until it ends on its own.
     *
     * @return the description of the cancelled command, or null when none was running
     */
    public static String cancelRemote() {
        String remote;
        synchronized (MONITOR) {
            remote = System.getProperty(REMOTE_PROPERTY);
            if (remote == null) return null;
            System.clearProperty(REMOTE_PROPERTY);
        }

        long threadId = Long.parseLong(remote.substring(0, remote.indexOf('|')));
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread.getId() == threadId) thread.interrupt();
        }
        return remoteDescription(remote);
    }

    /**
     * @return what keeps Drizzle busy, or null when it's idle
     */
    public static String busyDescription() {
        synchronized (MONITOR) {
            String remote = System.getProperty(REMOTE_PROPERTY);
            if (remote != null) {
                return remoteDescription(remote);
            }
            List<String> local = localEntries();
            return local.isEmpty() ? null : localDescription(local.get(0));
        }
    }

    /**
     * Forgets all activities; for tests only.
     */
    static void reset() {
        synchronized (MONITOR) {
            System.clearProperty(REMOTE_PROPERTY);
            System.clearProperty(LOCAL_PROPERTY);
        }
    }

    /**
     * @param remote "&lt;thread id&gt;|&lt;lease id&gt;|&lt;description&gt;"
     */
    private static String remoteDescription(final String remote) {
        return remote.substring(remote.indexOf('|', remote.indexOf('|') + 1) + 1);
    }

    /**
     * @param entry "&lt;lease id&gt;|&lt;description&gt;"
     */
    private static String localDescription(final String entry) {
        return entry.substring(entry.indexOf('|') + 1);
    }

    private static List<String> localEntries() {
        String value = System.getProperty(LOCAL_PROPERTY);
        return value == null || value.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(value.split("\n")));
    }

    private static void setLocalEntries(final List<String> entries) {
        if (entries.isEmpty()) {
            System.clearProperty(LOCAL_PROPERTY);
        } else {
            System.setProperty(LOCAL_PROPERTY, String.join("\n", entries));
        }
    }

    private static String newLeaseId() {
        return Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    private static String singleLine(final String description) {
        return description == null ? "" : description.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class RemoteLease implements Lease {
        private final String value;

        RemoteLease(final String value) {
            this.value = value;
        }

        @Override
        public boolean isHeld() {
            synchronized (MONITOR) {
                return value.equals(System.getProperty(REMOTE_PROPERTY));
            }
        }

        @Override
        public void close() {
            synchronized (MONITOR) {
                // after a cancel the property may already belong to the next command
                if (value.equals(System.getProperty(REMOTE_PROPERTY))) {
                    System.clearProperty(REMOTE_PROPERTY);
                }
            }
        }
    }

    private static final class LocalLease implements Lease {
        private final String entry;

        LocalLease(final String entry) {
            this.entry = entry;
        }

        @Override
        public boolean isHeld() {
            synchronized (MONITOR) {
                return localEntries().contains(entry);
            }
        }

        @Override
        public void close() {
            synchronized (MONITOR) {
                List<String> entries = localEntries();
                if (entries.remove(entry)) setLocalEntries(entries);
            }
        }
    }

    /**
     * An IDE action performed by the remote command's own thread; the remote lease already covers it.
     */
    private static final class NestedLease implements Lease {
        private volatile boolean closed;

        @Override
        public boolean isHeld() {
            return !closed;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
