package com.github.zhgzhg.drizzle.utils.process;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelaunchCommandTest {
    private static final List<String> LAUNCHER_OPTIONS = Arrays.asList("-Xmx512M", "-DAPP_DIR=X:\\Arduino");

    @TempDir
    Path tmp;

    private RelaunchCommand.Origin origin(final String os, final Path ideFolder, final List<String> options,
            final boolean console, final List<String> processCommandLine) {
        return new RelaunchCommand.Origin(os, ideFolder, tmp.resolve("jre"), options, "lib/pde.jar", "processing.app.Base",
                console, processCommandLine, tmp.resolve("work"));
    }

    private Path ideFolderWith(final String... files) throws Exception {
        Path folder = Files.createDirectories(tmp.resolve("Arduino"));
        for (String file : files) {
            Path created = Files.write(folder.resolve(file), new byte[0]);
            created.toFile().setExecutable(true);
        }
        return folder;
    }

    @Test
    void startsTheSameWindowsLauncherAgain() throws Exception {
        Path ide = ideFolderWith("arduino.exe", "arduino_debug.exe");

        RelaunchCommand gui = RelaunchCommand.of(origin("Windows 11", ide, LAUNCHER_OPTIONS, false, null));
        assertEquals(Arrays.asList("cmd.exe", "/c", "start", "\"\"", "\"" + ide.resolve("arduino.exe") + "\""), gui.command);
        assertEquals(ide, gui.directory);

        RelaunchCommand debug = RelaunchCommand.of(origin("Windows 11", ide, LAUNCHER_OPTIONS, true, null));
        assertEquals("\"" + ide.resolve("arduino_debug.exe") + "\"", debug.command.get(4), "the console means arduino_debug.exe");
    }

    @Test
    void repeatsTheJavaCommandLineWhenNoLauncherStartedTheIde() throws Exception {
        Path ide = ideFolderWith("arduino.exe");
        List<String> options = Collections.singletonList("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n");

        RelaunchCommand fromDevelopmentEnvironment = RelaunchCommand.of(origin("Windows 10", ide, options, true, null));
        assertEquals(Arrays.asList(tmp.resolve("jre").resolve("bin").resolve("java.exe").toString(), options.get(0),
                "-cp", "lib/pde.jar", "processing.app.Base"), fromDevelopmentEnvironment.command);
        assertEquals(tmp.resolve("work"), fromDevelopmentEnvironment.directory);

        RelaunchCommand withoutConsole = RelaunchCommand.of(origin("Windows 10", ide, options, false, null));
        assertEquals(tmp.resolve("jre").resolve("bin").resolve("javaw.exe").toString(), withoutConsole.command.get(0));

        Path withoutLaunchers = Files.createDirectories(tmp.resolve("portable-without-launchers"));
        RelaunchCommand launcherGone = RelaunchCommand.of(origin("Windows 10", withoutLaunchers, LAUNCHER_OPTIONS, false, null));
        assertEquals(tmp.resolve("jre").resolve("bin").resolve("javaw.exe").toString(), launcherGone.command.get(0));
    }

    @Test
    void opensTheMacAppBundleAgain() throws Exception {
        Path javaFolder = Files.createDirectories(tmp.resolve("Arduino.app").resolve("Contents").resolve("Java"));

        RelaunchCommand mac = RelaunchCommand.of(origin("Mac OS X", javaFolder, LAUNCHER_OPTIONS, false, null));
        assertEquals(Arrays.asList("/usr/bin/open", tmp.resolve("Arduino.app").toString()), mac.command);

        RelaunchCommand unbundled = RelaunchCommand.of(origin("Mac OS X", tmp, LAUNCHER_OPTIONS, false, null));
        assertEquals(tmp.resolve("jre").resolve("bin").resolve("java").toString(), unbundled.command.get(0));
    }

    @Test
    void runsTheLinuxScriptOrTheSameCommandLineAgain() throws Exception {
        List<String> processCommandLine = Arrays.asList("/usr/lib/jvm/java/bin/java", "-splash:./lib/splash.png",
                "processing.app.Base");

        Path withScript = ideFolderWith("arduino");
        assertEquals(Collections.singletonList(withScript.resolve("arduino").toString()),
                RelaunchCommand.of(origin("Linux", withScript, LAUNCHER_OPTIONS, false, processCommandLine)).command);

        // e.g. a distribution's package, whose launcher lives elsewhere
        Path packaged = Files.createDirectories(tmp.resolve("usr-share-arduino"));
        RelaunchCommand fromPackage = RelaunchCommand.of(origin("Linux", packaged, LAUNCHER_OPTIONS, false, processCommandLine));
        assertEquals(processCommandLine, fromPackage.command);
        assertEquals(tmp.resolve("work"), fromPackage.directory);

        assertEquals(tmp.resolve("jre").resolve("bin").resolve("java").toString(),
                RelaunchCommand.of(origin("Linux", packaged, LAUNCHER_OPTIONS, false, null)).command.get(0));
    }
}
