package com.github.zhgzhg.drizzle.utils.process;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Has files deleted and a command started once this JVM has exited, by {@link AfterExitHelper} running in a JVM of
 * its own: e.g. to start Arduino IDE again, and to delete a jar that stays locked on Windows while the IDE runs.
 * <p>
 * The helper notices the exit when the pipe to its standard input closes, which happens when this JVM exits.
 */
public final class AfterExit {
    private static final String HELPER_FOLDER = "drizzle-after-exit";
    private static final int MAX_OUTPUT_LINES = 20;

    // a pipe that gets garbage collected closes too, which the helper would take for the exit
    private static final List<Handle> WAITING = Collections.synchronizedList(new ArrayList<>());

    private AfterExit() { }

    /**
     * What to do after the exit.
     */
    public static final class Plan {
        final List<Path> deletions;
        final List<String> command;
        final Path directory;
        final Path log;
        final Long pid;

        /**
         * @param deletions the files to delete
         * @param command   the command to start afterwards; empty for none
         * @param directory the command's working directory; null for the helper's
         * @param log       where the helper reports what it does; null for nowhere
         * @param pid       this JVM's process id, to make sure it's gone; null when unknown
         */
        public Plan(final List<Path> deletions, final List<String> command, final Path directory, final Path log,
                final Long pid) {
            this.deletions = Collections.unmodifiableList(new ArrayList<>(deletions));
            this.command = Collections.unmodifiableList(new ArrayList<>(command));
            this.directory = directory;
            this.log = log;
            this.pid = pid;
        }
    }

    /**
     * A helper waiting for this JVM to exit.
     */
    public static final class Handle {
        private final Process process;
        private final Writer plan;

        private Handle(final Process process, final Writer plan) {
            this.process = process;
            this.plan = plan;
        }

        /**
         * Calls the plan off, e.g. because quitting got cancelled.
         */
        public void cancel() {
            WAITING.remove(this);
            try {
                plan.write(AfterExitHelper.CANCEL + "\n");
                plan.flush();
                plan.close();
                if (process.waitFor(5, TimeUnit.SECONDS)) return;
            } catch (IOException e) {
                // destroyed below
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process.destroyForcibly();
        }

        Process process() {
            return process;
        }
    }

    /**
     * @return the Java to run the helper with: on Windows the one without a console
     */
    public static Path javaExecutable(final Path javaHome) {
        Path bin = javaHome.resolve("bin");
        if (Files.isRegularFile(bin.resolve("javaw.exe"))) return bin.resolve("javaw.exe");
        if (Files.isRegularFile(bin.resolve("java.exe"))) return bin.resolve("java.exe");
        return bin.resolve("java");
    }

    /**
     * Starts the helper and returns once it's ready.
     *
     * @throws IOException when the helper couldn't start; the message includes what it reported
     */
    public static Handle start(final Path java, final Plan plan, final long readyTimeoutMs) throws IOException {
        List<String> lines = planLines(plan);
        if (plan.log != null) {
            Files.write(plan.log, Arrays.asList(stamp() + " plan: " + describe(plan)), StandardCharsets.UTF_8);
        }

        // run from a copy: running from the Drizzle jar would lock the jar, which may be one of the files to delete
        Path folder = Paths.get(System.getProperty("java.io.tmpdir"), HELPER_FOLDER);
        copyHelperClassInto(folder);

        // only ASCII on the command line: Java 8 on Windows passes it through the system code page
        Process process = new ProcessBuilder(java.toString(), "-Xmx32m", "-cp", ".", AfterExitHelper.class.getName())
                .directory(folder.toFile())
                .redirectErrorStream(true)
                .start();

        CountDownLatch ready = new CountDownLatch(1);
        List<String> output = Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> readOutput(process.getInputStream(), ready, output), "drizzle-after-exit-output");
        reader.setDaemon(true);
        reader.start();

        Writer writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        try {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
            writer.flush();

            if (ready.await(readyTimeoutMs, TimeUnit.MILLISECONDS)) {
                Handle handle = new Handle(process, writer);
                WAITING.add(handle);
                return handle;
            }
        } catch (IOException e) {
            // reported below, with what the helper said
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        process.destroyForcibly();
        String said;
        synchronized (output) {
            said = output.isEmpty() ? "" : ": " + String.join(" / ", output);
        }
        throw new IOException("The helper process didn't get ready" + said);
    }

    private static List<String> planLines(final Plan plan) {
        List<String> lines = new ArrayList<>();
        if (plan.log != null) lines.add(line("log", plan.log.toString()));
        if (plan.pid != null) lines.add(line("pid", plan.pid.toString()));
        for (Path file : plan.deletions) {
            lines.add(line("delete", file.toString()));
        }
        if (plan.directory != null) lines.add(line("dir", plan.directory.toString()));
        for (String argument : plan.command) {
            lines.add(line("run", argument));
        }
        lines.add(AfterExitHelper.GO);
        return lines;
    }

    private static String line(final String key, final String value) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("A line break in " + key + ": " + value);
        }
        return key + "\t" + value;
    }

    private static void copyHelperClassInto(final Path folder) throws IOException {
        String resource = AfterExitHelper.class.getName().replace('.', '/') + ".class";
        byte[] bytes;
        try (InputStream in = AfterExitHelper.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Can't find " + resource);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            bytes = buffer.toByteArray();
        }

        Path target = folder.resolve(resource);
        // a helper still waiting from before may run from the same file; leave it alone when it's the same
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) return;
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static void readOutput(final InputStream stream, final CountDownLatch ready, final List<String> output) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (AfterExitHelper.READY.equals(line)) {
                    ready.countDown();
                } else if (output.size() < MAX_OUTPUT_LINES) {
                    output.add(line);
                }
            }
        } catch (IOException e) {
            // the helper or this JVM is ending
        }
    }

    private static String describe(final Plan plan) {
        return "delete " + plan.deletions + ", then " + (plan.command.isEmpty() ? "nothing else" : "start " + plan.command)
                + (plan.directory != null ? " in " + plan.directory : "");
    }

    private static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
    }
}
