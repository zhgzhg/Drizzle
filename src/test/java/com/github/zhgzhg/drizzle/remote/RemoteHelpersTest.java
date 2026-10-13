package com.github.zhgzhg.drizzle.remote;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteHelpersTest {

    @TempDir
    Path tmp;

    @Test
    void parsesGccDiagnostics() {
        String output = "C:\\Users\\me\\Arduino\\blink\\blink.ino: In function 'void loop()':\n"
                + "C:\\Users\\me\\Arduino\\blink\\blink.ino:12:3: error: 'x' was not declared in this scope\r\n"
                + "   x = 1;\n"
                + "/home/me/blink/util.h:4:1: warning: unused variable 'y' [-Wunused-variable]\n"
                + "/home/me/blink/util.h:4:1: warning: unused variable 'y' [-Wunused-variable]\n"
                + "blink.ino:7: note: in a file without columns\n"
                + "exit status 1\n";

        List<Map<String, Object>> diagnostics = CompilerDiagnostics.parse(output);
        assertEquals(3, diagnostics.size());

        assertEquals("C:\\Users\\me\\Arduino\\blink\\blink.ino", diagnostics.get(0).get("file"));
        assertEquals(12, diagnostics.get(0).get("line"));
        assertEquals(3, diagnostics.get(0).get("column"));
        assertEquals("error", diagnostics.get(0).get("severity"));
        assertEquals("'x' was not declared in this scope", diagnostics.get(0).get("message"));

        assertEquals("warning", diagnostics.get(1).get("severity"));
        assertFalse(diagnostics.get(2).containsKey("column"));
    }

    @Test
    void resolvesTheTabNamesArduinoPrintsFromTheCompilersContext() {
        // Arduino IDE rewrites error lines of files named like a sketch tab to "<tab name>:line:col"
        String output = "C:\\Sketches\\blink\\blink.ino: In function 'void setup()':\n"
                + "blink:4:11: error: 'LED_BUILTIN' was not declared in this scope\n"
                + "In file included from C:\\Sketches\\blink\\blink.ino:1:0:\n"
                + "C:\\Libraries\\Lib\\src\\config.h: In function 'void configure()':\n"
                + "config.h:5:3: error: 'y' was not declared in this scope\n"
                + "other.h:9:1: error: no context names this file\n";

        List<Map<String, Object>> diagnostics = CompilerDiagnostics.parse(output);
        assertEquals("C:\\Sketches\\blink\\blink.ino", diagnostics.get(0).get("file"));
        assertEquals("C:\\Libraries\\Lib\\src\\config.h", diagnostics.get(1).get("file"),
                "a library header stays apart from a sketch tab that has the same name");
        assertEquals("other.h", diagnostics.get(2).get("file"));
    }

    @Test
    void capturesConsoleOutputAsItsPrintedAndRestoresTheStreams() {
        PrintStream out = System.out;
        PrintStream err = System.err;
        List<String> log = new ArrayList<>();

        OutputCapture capture = OutputCapture.start(log::add);
        try {
            System.out.print("Installing platform...");
            assertEquals("Installing platform...", String.join("", log), "partial lines reach the log right away");
            System.out.println(" done");
            System.err.println("bad thing");
            System.err.print("unterminated");
        } finally {
            capture.close();
        }

        assertSame(out, System.out);
        assertSame(err, System.err);
        String nl = System.lineSeparator();
        assertEquals("Installing platform... done" + nl + "bad thing" + nl + "unterminated", String.join("", log));
        assertEquals("bad thing", capture.errorLines().get(0));
        assertEquals("unterminated", capture.errorLines().get(1));
    }

    @Test
    void keepsCharactersSplitBetweenWritesIntact() {
        Assumptions.assumeTrue(Charset.defaultCharset().equals(StandardCharsets.UTF_8), "needs a UTF-8 default charset");
        List<String> log = new ArrayList<>();

        OutputCapture capture = OutputCapture.start(log::add);
        try {
            byte[] cyrillic = "ж".getBytes(StandardCharsets.UTF_8);
            System.out.write(cyrillic[0]);
            System.out.flush();
            System.out.write(cyrillic[1]);
            System.out.flush();
        } finally {
            capture.close();
        }

        assertEquals("ж", String.join("", log));
    }

    @Test
    void writesLaunchersThatSurviveSpecialCharacters() {
        String posix = Launcher.posixScript("/opt/it's/java", "/opt/tools/drizzle.jar", "1.0");
        assertTrue(posix.startsWith("#!/bin/sh\n"));
        assertTrue(posix.contains("exec '/opt/it'\\''s/java' -jar '/opt/tools/drizzle.jar' \"$@\"\n"), posix);

        String windows = Launcher.windowsScript("C:\\100% Arduino\\java.exe", "C:\\tools\\drizzle.jar", "1.0");
        assertTrue(windows.endsWith("\"C:\\100%% Arduino\\java.exe\" -jar \"C:\\tools\\drizzle.jar\" %*\r\n"), windows);
        assertFalse(windows.contains("chcp"), "ASCII paths need no code page switch");

        String nonAscii = Launcher.windowsScript("C:\\Users\\Иван\\arduino\\java\\bin\\java.exe", "C:\\tools\\drizzle.jar", "1.0");
        assertTrue(nonAscii.contains("chcp 65001 >nul\r\n\"C:\\Users\\Иван\\arduino\\java\\bin\\java.exe\" -jar"), nonAscii);
        assertTrue(nonAscii.endsWith("exit /b %DRIZZLE_EXIT%\r\n"), "keeps java's exit code after restoring the code page");
    }

    @Test
    void rewritesTheLauncherOnlyWhenItChanges() throws Exception {
        Path java = Paths.get("C:\\java\\bin\\java.exe");
        Path script = Launcher.write(tmp, java, Paths.get("C:\\tools\\drizzle-1.jar"), "1");
        FileTime old = FileTime.fromMillis(1000000000000L);
        Files.setLastModifiedTime(script, old);

        Launcher.write(tmp, java, Paths.get("C:\\tools\\drizzle-1.jar"), "1");
        assertEquals(old, Files.getLastModifiedTime(script));

        Launcher.write(tmp, java, Paths.get("C:\\tools\\drizzle-2.jar"), "2");
        assertTrue(new String(Files.readAllBytes(script), StandardCharsets.UTF_8).contains("drizzle-2.jar"));
        try (java.util.stream.Stream<Path> files = Files.list(tmp)) {
            assertEquals(1, files.count(), "no temporary files left behind");
        }
    }

    @Test
    void escapesNonAsciiCharactersInJson() {
        assertEquals("{\"a\":\"\\u0431 \\u00e9 \\u20ac\"}", RemoteCLI.asciiOnly("{\"a\":\"б é €\"}"));
        assertEquals("{\"a\":\"plain\"}", RemoteCLI.asciiOnly("{\"a\":\"plain\"}"));
    }
}
