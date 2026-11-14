package com.github.zhgzhg.drizzle.utils.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AfterExitTest {
    private static final boolean WINDOWS = File.separatorChar == '\\';

    @TempDir
    Path tmp;

    private static Path java() {
        return AfterExit.javaExecutable(Paths.get(System.getProperty("java.home")));
    }

    /**
     * @return a command that creates the file
     */
    private static List<String> touch(final Path file) {
        return WINDOWS ? Arrays.asList("cmd.exe", "/c", "copy", "NUL", file.toString()) : Arrays.asList("touch", file.toString());
    }

    private static boolean waitFor(final long timeoutMs, final java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Test
    void deletesAndStartsOnceTheStartingProcessHasExited() throws Exception {
        // a name outside the system code page, which Java 8 on Windows can't pass on the command line
        Path old = Files.write(tmp.resolve("старият drizzle.jar"), new byte[] {1, 2, 3});
        Path started = tmp.resolve("started");
        Path log = tmp.resolve("restart.log");

        AfterExit.Handle helper = AfterExit.start(java(),
                new AfterExit.Plan(Collections.singletonList(old), touch(started), tmp, log, null), 30000);

        Thread.sleep(500);
        assertTrue(Files.exists(old), "nothing happens while the starting process runs");
        assertFalse(Files.exists(started));

        helper.process().getOutputStream().close(); // what this JVM's exit does to the pipe
        assertTrue(waitFor(15000, () -> Files.exists(started)), "starts the command");
        assertFalse(Files.exists(old), "deletes the file first");
        assertTrue(helper.process().waitFor(15, TimeUnit.SECONDS), "and ends");

        String report = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        assertTrue(report.contains("the process exited"), report);
        assertTrue(report.contains("deleted " + old), report);
    }

    @Test
    void doesNothingWhenCancelled() throws Exception {
        Path old = Files.write(tmp.resolve("old.jar"), new byte[] {1});
        Path started = tmp.resolve("started");

        AfterExit.Handle helper = AfterExit.start(java(),
                new AfterExit.Plan(Collections.singletonList(old), touch(started), tmp, tmp.resolve("restart.log"), null),
                30000);
        helper.cancel();

        assertTrue(helper.process().waitFor(15, TimeUnit.SECONDS));
        Thread.sleep(500);
        assertTrue(Files.exists(old));
        assertFalse(Files.exists(started));
    }

    @Test
    void refusesPlansItCantPassOn() {
        AfterExit.Plan plan = new AfterExit.Plan(Collections.emptyList(), Arrays.asList("echo", "two\nlines"), null, null,
                null);
        assertThrows(IllegalArgumentException.class, () -> AfterExit.start(java(), plan, 30000));
    }
}
