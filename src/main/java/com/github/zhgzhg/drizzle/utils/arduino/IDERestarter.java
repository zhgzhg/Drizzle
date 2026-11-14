package com.github.zhgzhg.drizzle.utils.arduino;

import cc.arduino.packages.Uploader;
import com.github.zhgzhg.drizzle.remote.ActivityGuard;
import com.github.zhgzhg.drizzle.utils.process.AfterExit;
import com.github.zhgzhg.drizzle.utils.process.RelaunchCommand;
import processing.app.Base;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Restarts Arduino IDE: quits it the way File &rarr; Quit does, and has it started again, the way it was started,
 * once it has exited. Also deletes files that can only go once the IDE has exited, such as Drizzle's replaced jar,
 * which stays locked on Windows while any editor window has it loaded.
 * <p>
 * Every editor window loads Drizzle in a class loader of its own, so the state shared between them lives in system
 * properties.
 */
public final class IDERestarter {
    public static final String ACTIVITY = "Restart Arduino IDE";

    private static final Object MONITOR = "com.github.zhgzhg.drizzle.utils.arduino.IDERestarter.MONITOR";
    private static final String DELETIONS_PROPERTY = "drizzle.afterExit.deletions";
    private static final String CLEANUP_HOOK_PROPERTY = "drizzle.afterExit.cleanupHook";
    private static final String RESTARTING_PROPERTY = "drizzle.restarting";
    private static final String LOG_FILE = "drizzle-restart.log";
    private static final long READY_TIMEOUT_MS = 30000;
    private static final long CLEANUP_READY_TIMEOUT_MS = 10000;

    private IDERestarter() { }

    /**
     * Deletes the file once the IDE has exited: right before a restart started again, or after a plain exit.
     */
    public static void deleteAfterExit(final Path file) {
        synchronized (MONITOR) {
            Set<String> deletions = new LinkedHashSet<>(deletionsAsStrings());
            deletions.add(file.toAbsolutePath().toString());
            System.setProperty(DELETIONS_PROPERTY, String.join("\n", deletions));

            if (System.getProperty(CLEANUP_HOOK_PROPERTY) == null) {
                Runtime.getRuntime().addShutdownHook(new Thread(IDERestarter::cleanUpAfterExit, "drizzle-after-exit"));
                System.setProperty(CLEANUP_HOOK_PROPERTY, "registered");
            }
        }
    }

    /**
     * Quits the IDE like File &rarr; Quit, which asks about unsaved changes, and has it started again once it has
     * exited. Call it off the event dispatch thread.
     *
     * @return why the IDE didn't restart; when it does, this doesn't return
     */
    public static String restart() {
        // quitting would interrupt e.g. a platform install or an upload
        ActivityGuard.Lease lease = ActivityGuard.tryLocalAlone(ACTIVITY);
        if (lease == null) {
            return "Drizzle is busy with " + Objects.toString(ActivityGuard.busyDescription(), "another action")
                    + "; try again once it's done.";
        }

        Path log = logFile();
        AfterExit.Handle helper = null;
        try {
            // the IDE's quit would stop it, possibly leaving the board half written
            if (isUploaderRunning()) return "An upload or a bootloader burn is running; try again once it's done.";
            Base base = Base.INSTANCE;
            if (base == null) return "Arduino IDE is still starting.";

            RelaunchCommand relaunch = RelaunchCommand.of(RelaunchCommand.Origin.ofThisJvm(ideFolder()));
            try {
                helper = AfterExit.start(AfterExit.javaExecutable(javaHome()),
                        new AfterExit.Plan(deletions(), relaunch.command, relaunch.directory, log, currentPid()),
                        READY_TIMEOUT_MS);
            } catch (IOException | RuntimeException e) {
                return "Couldn't arrange the restart: " + e.getMessage() + " (details in " + log + ")";
            }
            System.setProperty(RESTARTING_PROPERTY, "true"); // the helper takes care of the deletions

            if (quit(base)) {
                System.exit(0); // on macOS the IDE leaves exiting to the system's Quit menu
            }
            return "Quitting got cancelled.";
        } catch (InvocationTargetException e) {
            return "Quitting failed: " + e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted.";
        } catch (RuntimeException e) {
            return "Quitting failed: " + e;
        } finally {
            // reached only when the IDE keeps running
            if (helper != null) {
                System.clearProperty(RESTARTING_PROPERTY);
                helper.cancel();
            }
            lease.close();
        }
    }

    /**
     * @return whether the IDE quits; on Windows and Linux it exits instead of returning true
     */
    private static boolean quit(final Base base) throws InvocationTargetException, InterruptedException {
        if (SwingUtilities.isEventDispatchThread()) return base.handleQuit();

        AtomicBoolean quitting = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> quitting.set(base.handleQuit()));
        return quitting.get();
    }

    /**
     * Runs when the IDE exits other than through {@link #restart()}.
     */
    private static void cleanUpAfterExit() {
        if (System.getProperty(RESTARTING_PROPERTY) != null) return;

        List<Path> deletions = deletions();
        if (deletions.isEmpty()) return;
        try {
            AfterExit.start(AfterExit.javaExecutable(javaHome()),
                    new AfterExit.Plan(deletions, Collections.emptyList(), null, logFile(), currentPid()),
                    CLEANUP_READY_TIMEOUT_MS);
        } catch (IOException | RuntimeException e) {
            // nothing else can be done while exiting
        }
    }

    private static boolean isUploaderRunning() {
        try {
            Process uploader = Uploader.programmerPid;
            return uploader != null && uploader.isAlive();
        } catch (LinkageError e) {
            return false; // an IDE without that field
        }
    }

    /**
     * @return the folder with the IDE's launchers: the parent of the {@code lib} folder with its own classes
     */
    static Path ideFolder() {
        try {
            Path pde = Paths.get(Base.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path lib = pde.getParent();
            if (lib != null && lib.getParent() != null) return lib.getParent();
        } catch (Exception e) {
            // the launchers tell it too
        }
        String appDir = System.getProperty("APP_DIR");
        return Paths.get(appDir != null ? appDir : System.getProperty("user.dir"));
    }

    private static List<Path> deletions() {
        List<Path> paths = new ArrayList<>();
        for (String path : deletionsAsStrings()) {
            paths.add(Paths.get(path));
        }
        return paths;
    }

    private static List<String> deletionsAsStrings() {
        String deletions = System.getProperty(DELETIONS_PROPERTY, "");
        return deletions.isEmpty() ? Collections.emptyList() : Arrays.asList(deletions.split("\n"));
    }

    private static Path javaHome() {
        return Paths.get(System.getProperty("java.home"));
    }

    private static Path logFile() {
        return Paths.get(System.getProperty("java.io.tmpdir"), LOG_FILE);
    }

    /**
     * Java 8 has no ProcessHandle; the runtime's name is "&lt;pid&gt;@&lt;host&gt;".
     */
    private static Long currentPid() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        try {
            return Long.parseLong(name.substring(0, name.indexOf('@')));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
