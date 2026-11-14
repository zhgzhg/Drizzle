package com.github.zhgzhg.drizzle.utils.process;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Runs in a JVM of its own, started by {@link AfterExit}: waits until the process that started it has exited, then
 * deletes files and starts a command, e.g. Arduino IDE again. It notices the exit through its standard input: a pipe
 * the starting process holds open until it exits.
 * <p>
 * It gets copied alone into a folder of its own and run from there, so it must not use other Drizzle classes, nor
 * classes of its own (no inner or anonymous classes).
 * <p>
 * Its standard input carries the plan, one tab-separated key and value per line: {@code log <file>},
 * {@code pid <process id>}, {@code delete <file>} (any number), {@code dir <folder>}, {@code run <argument>} (one per
 * argument), and then {@code go}. It answers {@code ready} on its standard output, and never writes there again. A
 * later {@code cancel} line makes it quit without doing anything.
 */
public final class AfterExitHelper {
    static final String GO = "go";
    static final String READY = "ready";
    static final String CANCEL = "cancel";

    private static final long DELETE_RETRY_MS = 15000;
    private static final long GONE_WAIT_MS = 10000;
    private static final long POLL_MS = 250;

    private static Path log;

    private AfterExitHelper() { }

    public static void main(final String[] args) {
        try {
            run();
        } catch (Throwable t) {
            log("failed: " + t);
        }
    }

    private static void run() throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String pid = null;
        List<Path> deletions = new ArrayList<>();
        File directory = null;
        List<String> command = new ArrayList<>();

        String line;
        while ((line = in.readLine()) != null && !GO.equals(line)) {
            int tab = line.indexOf('\t');
            if (tab < 0) continue;
            String value = line.substring(tab + 1);
            switch (line.substring(0, tab)) {
                case "log": log = Paths.get(value); break;
                case "pid": pid = value; break;
                case "delete": deletions.add(Paths.get(value)); break;
                case "dir": directory = new File(value); break;
                case "run": command.add(value); break;
                default: break;
            }
        }
        if (line == null) return; // the starting process went away before handing over the whole plan

        System.out.println(READY);
        System.out.flush();
        log("waiting for the process to exit");

        try {
            while ((line = in.readLine()) != null) {
                if (CANCEL.equals(line)) {
                    log("cancelled");
                    return;
                }
            }
        } catch (IOException e) {
            // a broken pipe: the starting process is gone
        }
        log("the process exited");

        waitUntilGone(pid);
        for (Path file : deletions) {
            delete(file);
        }
        if (!command.isEmpty()) start(command, directory);
    }

    /**
     * The pipe closes while the process ends; on macOS, an app that's still ending would only get activated again.
     */
    private static void waitUntilGone(final String pid) {
        if (pid == null || File.separatorChar == '\\') return;

        long deadline = System.currentTimeMillis() + GONE_WAIT_MS;
        while (System.currentTimeMillis() < deadline && isRunning(pid)) {
            sleep(POLL_MS);
        }
    }

    private static boolean isRunning(final String pid) {
        if (Files.isDirectory(Paths.get("/proc/self"))) {
            return Files.exists(Paths.get("/proc", pid));
        }
        try {
            Process kill = new ProcessBuilder("kill", "-0", pid).redirectErrorStream(true)
                    .redirectOutput(nullFile()).start();
            return kill.waitFor() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Retries for a while: on Windows, the files of a process that just ended can stay in use for a moment.
     */
    private static void delete(final Path file) {
        long deadline = System.currentTimeMillis() + DELETE_RETRY_MS;
        while (true) {
            try {
                log((Files.deleteIfExists(file) ? "deleted " : "already gone: ") + file);
                return;
            } catch (IOException e) {
                if (System.currentTimeMillis() >= deadline) {
                    log("couldn't delete " + file + ": " + e);
                    return;
                }
                sleep(POLL_MS);
            }
        }
    }

    private static void start(final List<String> command, final File directory) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(nullFile()))
                    .redirectOutput(nullFile())
                    .redirectError(nullFile());
            if (directory != null) builder.directory(directory);
            builder.start();
            log("started " + command + (directory != null ? " in " + directory : ""));
        } catch (IOException e) {
            log("couldn't start " + command + ": " + e);
        }
    }

    private static File nullFile() {
        return new File(File.separatorChar == '\\' ? "NUL" : "/dev/null");
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void log(final String message) {
        if (log == null) return;
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
        try {
            Files.write(log, (stamp + " helper: " + message + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // nowhere else to report it
        }
    }
}
