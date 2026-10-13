package com.github.zhgzhg.drizzle.remote;

import com.github.zhgzhg.drizzle.DrizzleCLI;
import com.github.zhgzhg.drizzle.utils.arduino.UpdateUtils;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts Drizzle's remote control once per IDE process: the HTTP server, the instance file advertising it, and the
 * {@code ~/.drizzle/bin} launcher. The server isn't tied to the window that started it; it serves all of them.
 */
public final class RemoteControl {
    // the IDE gives each window its own class loader, so "already running" is kept in a (JVM-wide) system property
    private static final String RUNNING_PROPERTY = "drizzle.remote.instance";
    private static final Object MONITOR = "com.github.zhgzhg.drizzle.remote.RemoteControl.MONITOR";
    private static final long REFRESH_SECONDS = 5;

    private RemoteControl() { }

    public static void ensureStarted(final LogProxy<?> logProxy) {
        synchronized (MONITOR) {
            if (System.getProperty(RUNNING_PROPERTY) != null) return;

            try {
                String instanceId = start(logProxy);
                System.setProperty(RUNNING_PROPERTY, instanceId);
            } catch (Exception e) {
                logProxy.cliError("Drizzle remote control is unavailable: %s%n", e);
            }
        }
    }

    private static String start(final LogProxy<?> logProxy) throws IOException, RemoteException {
        IDERemoteCommandHandler handler = new IDERemoteCommandHandler();
        RemoteServer server = RemoteServer.start(handler, randomHex(8));

        InstanceDirectory directory = InstanceDirectory.defaultLocation();
        InstanceInfo info = new InstanceInfo();
        Path file;
        try {
            info.instanceId = server.instanceId();
            info.pid = currentPid();
            info.host = server.host();
            info.port = server.port();
            info.token = server.token();
            info.drizzleVersion = DrizzleCLI.version();
            info.ideVersion = UpdateUtils.arduinoVersion();
            info.startedAt = Instant.now().toString();
            info.sketches = handler.openSketchPaths(); // may be empty while the IDE still restores its windows
            file = directory.write(info);
        } catch (IOException | RemoteException | RuntimeException e) {
            server.stop();
            throw e;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            directory.delete(file);
            server.stop();
        }, "drizzle-remote-cleanup"));

        ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "drizzle-remote-refresh");
            t.setDaemon(true);
            return t;
        });
        // keeps the sketch list current, and recreates the file if it got deleted (e.g. by a client that timed out)
        refresher.scheduleWithFixedDelay(() -> {
            try {
                List<String> sketches = handler.openSketchPaths();
                if (!sketches.equals(info.sketches) || !Files.exists(file)) {
                    info.sketches = sketches;
                    directory.write(info);
                }
            } catch (Exception e) {
                // the UI is busy or the file is being read; try again on the next round
            }
        }, REFRESH_SECONDS, REFRESH_SECONDS, TimeUnit.SECONDS);

        Path launcher = writeLauncher(logProxy);
        logProxy.cliInfo("Drizzle remote control: listening on %s:%d, instance %s%s%n", info.host, info.port, info.instanceId,
                launcher != null ? ", CLI " + launcher : "");
        return info.instanceId;
    }

    private static Path writeLauncher(final LogProxy<?> logProxy) {
        try {
            Path jar = Paths.get(RemoteControl.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(jar) || !jar.toString().toLowerCase().endsWith(".jar")) {
                return null; // not running from the installed jar, e.g. during development
            }
            Path java = Launcher.javaExecutable(Paths.get(System.getProperty("java.home")));
            return Launcher.write(Launcher.defaultDirectory(), java, jar, DrizzleCLI.version());
        } catch (Exception e) {
            logProxy.cliError("Couldn't write the Drizzle CLI launcher: %s%n", e);
            return null;
        }
    }

    private static long currentPid() {
        // Java 8 has no ProcessHandle; the runtime's name is "<pid>@<host>"
        String name = ManagementFactory.getRuntimeMXBean().getName();
        try {
            return Long.parseLong(name.substring(0, name.indexOf('@')));
        } catch (RuntimeException e) {
            return Math.abs(new SecureRandom().nextInt());
        }
    }

    private static String randomHex(final int bytes) {
        byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        StringBuilder sb = new StringBuilder();
        for (byte b : random) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
