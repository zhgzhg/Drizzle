package com.github.zhgzhg.drizzle.remote;

import java.security.SecureRandom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the submitted commands one at a time on a dedicated thread and keeps the most recent jobs around, so
 * clients can poll for their results.
 * <p>
 * A running job can be cancelled through {@link #cancel(String)} or from the IDE (Tools &rarr; Drizzle), which ends
 * its {@link ActivityGuard} lease. A cancelled job is marked failed right away and a fresh thread serves the next
 * jobs, because its own thread may stay stuck in a blocking operation for a while.
 */
public final class JobManager {
    private static final int MAX_KEPT_JOBS = 20;
    private static final String CANCELLED_FROM_IDE = "Cancelled from Arduino IDE (Tools > Drizzle > Cancel Remote Command)";

    private final RemoteCommandHandler handler;
    private final SecureRandom random = new SecureRandom();

    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private ExecutorService worker = newWorker();
    private Job current;
    private ActivityGuard.Lease currentLease;
    private Thread currentThread;
    private Job last;

    public JobManager(final RemoteCommandHandler handler) {
        this.handler = handler;
    }

    public synchronized Job submit(final String command, final Map<String, String> args) throws RemoteException {
        if (!handler.supports(command)) {
            throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "Unknown command: " + command);
        }

        dropCancelledJob();
        if (current != null) {
            throw new RemoteException(RemoteProtocol.ERR_BUSY,
                    "Another remote command is running: " + current.getCommand() + " (job " + current.getId() + ")");
        }
        boolean exclusive = handler.isExclusive(command);
        String busy = ActivityGuard.busyDescription();
        if (exclusive && busy != null) {
            throw new RemoteException(RemoteProtocol.ERR_BUSY, "Drizzle is busy with " + busy);
        }

        Job job = new Job(newId(), command, args);
        current = job;
        last = job;
        jobs.put(job.getId(), job);
        dropOldJobs();

        worker.execute(() -> run(job, exclusive));
        return job;
    }

    public synchronized Job get(final String id) {
        dropCancelledJob();
        return jobs.get(id);
    }

    public synchronized Job getCurrent() {
        dropCancelledJob();
        return current;
    }

    public synchronized Job getLast() {
        dropCancelledJob();
        return last;
    }

    /**
     * @return the job, now marked cancelled if it was still running
     */
    public synchronized Job cancel(final String id) throws RemoteException {
        Job job = get(id);
        if (job == null) {
            throw new RemoteException(RemoteProtocol.ERR_NOT_FOUND, "No such job: " + id);
        }

        if (job == current) {
            if (currentLease != null) {
                ActivityGuard.cancelRemote();
            } else if (currentThread != null) {
                currentThread.interrupt();
            }
            abandonCurrent("Cancelled through Drizzle's remote API; a compilation or download it already started may still"
                    + " finish in the background");
        }
        return job;
    }

    public synchronized void shutdown() {
        worker.shutdownNow();
    }

    private void run(final Job job, final boolean exclusive) {
        ActivityGuard.Lease lease = null;
        try {
            if (exclusive) {
                lease = ActivityGuard.tryRemote("remote " + job.getCommand() + " (job " + job.getId() + ")");
                if (lease == null) {
                    job.fail(RemoteProtocol.ERR_BUSY, "Drizzle is busy with " + ActivityGuard.busyDescription());
                    return;
                }
            }

            synchronized (this) {
                if (current != job) return; // cancelled through the API before it started
                if (lease != null && !lease.isHeld()) { // cancelled from the IDE before it started
                    abandonCurrent(CANCELLED_FROM_IDE);
                    return;
                }
                currentLease = lease;
                currentThread = Thread.currentThread();
            }

            Map<String, Object> result = handler.execute(job.getCommand(), job.getArgs(), job::appendLog);
            failIfCancelledFromIde(job, lease);
            job.succeed(result);
        } catch (RemoteException e) {
            failIfCancelledFromIde(job, lease);
            job.fail(e.getCode(), e.getMessage());
        } catch (Throwable t) {
            failIfCancelledFromIde(job, lease);
            job.fail(RemoteProtocol.ERR_INTERNAL, t.toString());
        } finally {
            if (lease != null) lease.close();
            synchronized (this) {
                if (current == job) {
                    current = null;
                    currentLease = null;
                    currentThread = null;
                }
            }
        }
    }

    /**
     * Notices a cancel made from the IDE: the job's lease isn't held anymore.
     */
    private void dropCancelledJob() {
        if (current != null && currentLease != null && !currentLease.isHeld()) {
            abandonCurrent(CANCELLED_FROM_IDE);
        }
    }

    /**
     * The interrupt of a cancel often makes the command end right away (e.g. a compile fails), possibly before anyone
     * asked about the job; its outcome is then the cancel, not that failure.
     */
    private static void failIfCancelledFromIde(final Job job, final ActivityGuard.Lease lease) {
        if (lease != null && !lease.isHeld()) {
            job.fail(RemoteProtocol.ERR_CANCELLED, CANCELLED_FROM_IDE);
        }
    }

    private void abandonCurrent(final String reason) {
        current.fail(RemoteProtocol.ERR_CANCELLED, reason);
        current = null;
        currentLease = null;
        currentThread = null;

        // the cancelled job's thread may stay blocked for a while; later jobs get a new one
        worker.shutdownNow();
        worker = newWorker();
    }

    private void dropOldJobs() {
        Iterator<Job> it = jobs.values().iterator();
        while (jobs.size() > MAX_KEPT_JOBS && it.hasNext()) {
            if (it.next() != current) it.remove();
        }
    }

    private String newId() {
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static ExecutorService newWorker() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "drizzle-remote-job");
            t.setDaemon(true);
            return t;
        });
    }
}
