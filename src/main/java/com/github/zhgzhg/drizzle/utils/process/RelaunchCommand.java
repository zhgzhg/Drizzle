package com.github.zhgzhg.drizzle.utils.process;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * How to start Arduino IDE again the way it was started: with the same launcher when that's known, otherwise with
 * the same Java command line.
 */
public final class RelaunchCommand {
    public static final String DEFAULT_MAIN_CLASS = "processing.app.Base";

    public final List<String> command;
    public final Path directory;

    private RelaunchCommand(final List<String> command, final Path directory) {
        this.command = Collections.unmodifiableList(new ArrayList<>(command));
        this.directory = directory;
    }

    /**
     * What a running IDE knows about how it was started.
     */
    public static final class Origin {
        final String osName;
        final Path ideFolder;
        final Path javaHome;
        final List<String> jvmOptions;
        final String classPath;
        final String mainClass;
        final boolean hasConsole;
        final List<String> processCommandLine;
        final Path workingDirectory;

        /**
         * @param ideFolder          the folder with the IDE's launchers and its {@code lib} folder
         * @param hasConsole         whether the JVM runs in a console, e.g. started by {@code arduino_debug.exe}
         * @param processCommandLine the process's own command line where the system tells it (Linux), otherwise null
         */
        Origin(final String osName, final Path ideFolder, final Path javaHome, final List<String> jvmOptions,
                final String classPath, final String mainClass, final boolean hasConsole,
                final List<String> processCommandLine, final Path workingDirectory) {
            this.osName = osName;
            this.ideFolder = ideFolder;
            this.javaHome = javaHome;
            this.jvmOptions = jvmOptions;
            this.classPath = classPath;
            this.mainClass = mainClass;
            this.hasConsole = hasConsole;
            this.processCommandLine = processCommandLine;
            this.workingDirectory = workingDirectory;
        }

        /**
         * @param ideFolder the folder with the IDE's launchers and its {@code lib} folder
         */
        public static Origin ofThisJvm(final Path ideFolder) {
            String command = System.getProperty("sun.java.command", "").trim();
            String mainClass = command.isEmpty() ? DEFAULT_MAIN_CLASS : command.split("\\s+", 2)[0];
            return new Origin(System.getProperty("os.name", ""), ideFolder, Paths.get(System.getProperty("java.home")),
                    ManagementFactory.getRuntimeMXBean().getInputArguments(), System.getProperty("java.class.path", ""),
                    mainClass, System.console() != null, procCommandLine(), Paths.get(System.getProperty("user.dir")));
        }

        private static List<String> procCommandLine() {
            Path cmdline = Paths.get("/proc/self/cmdline");
            if (!Files.isReadable(cmdline)) return null;
            try {
                // file names and arguments are bytes there; Java decodes them like file names
                Charset charset = Charset.forName(System.getProperty("sun.jnu.encoding", Charset.defaultCharset().name()));
                String all = new String(Files.readAllBytes(cmdline), charset);
                List<String> arguments = new ArrayList<>(Arrays.asList(all.split("\u0000")));
                while (!arguments.isEmpty() && arguments.get(arguments.size() - 1).isEmpty()) {
                    arguments.remove(arguments.size() - 1);
                }
                return arguments.isEmpty() ? null : arguments;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    public static RelaunchCommand of(final Origin origin) {
        String os = origin.osName.toLowerCase(Locale.ROOT);

        if (os.startsWith("windows")) {
            // arduino.exe and arduino_debug.exe pass the IDE's folder as APP_DIR; the latter runs Java in a console
            boolean byLauncher = origin.jvmOptions.stream().anyMatch(option -> option.startsWith("-DAPP_DIR="));
            Path launcher = origin.ideFolder.resolve(origin.hasConsole ? "arduino_debug.exe" : "arduino.exe");
            if (byLauncher && Files.isRegularFile(launcher)) {
                // "start" opens a console launcher in a console window of its own, as from the desktop; the quotes
                // keep cmd from interpreting characters such as & in the path
                return new RelaunchCommand(Arrays.asList("cmd.exe", "/c", "start", "\"\"", "\"" + launcher + "\""),
                        origin.ideFolder);
            }
        } else if (os.contains("mac")) {
            // .../Arduino.app/Contents/Java
            Path contents = origin.ideFolder.getParent();
            Path bundle = contents != null ? contents.getParent() : null;
            if (bundle != null && bundle.getFileName() != null && bundle.getFileName().toString().endsWith(".app")
                    && "Java".equals(String.valueOf(origin.ideFolder.getFileName()))
                    && "Contents".equals(String.valueOf(contents.getFileName()))) {
                return new RelaunchCommand(Arrays.asList("/usr/bin/open", bundle.toString()), origin.workingDirectory);
            }
        } else {
            Path script = origin.ideFolder.resolve("arduino");
            if (Files.isRegularFile(script) && Files.isExecutable(script)) {
                return new RelaunchCommand(Collections.singletonList(script.toString()), origin.workingDirectory);
            }
            if (origin.processCommandLine != null) {
                return new RelaunchCommand(origin.processCommandLine, origin.workingDirectory);
            }
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable(origin).toString());
        command.addAll(origin.jvmOptions);
        command.add("-cp");
        command.add(origin.classPath);
        command.add(origin.mainClass);
        return new RelaunchCommand(command, origin.workingDirectory);
    }

    private static Path javaExecutable(final Origin origin) {
        Path bin = origin.javaHome.resolve("bin");
        if (origin.osName.toLowerCase(Locale.ROOT).startsWith("windows")) {
            return bin.resolve(origin.hasConsole ? "java.exe" : "javaw.exe");
        }
        return bin.resolve("java");
    }

    @Override
    public String toString() {
        return command + " in " + directory;
    }
}
