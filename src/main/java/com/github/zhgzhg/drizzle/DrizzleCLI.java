package com.github.zhgzhg.drizzle;

import com.github.zhgzhg.drizzle.utils.json.ProjectSettings;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.source.SourceExtractor;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DrizzleCLI {

    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("-h") || args[0].equals("--help"))) {
            String implementationVersion = version();

            System.out.printf("Drizzle %s CLI Helper%n", implementationVersion);

            System.out.println("\tParses Arduino IDE sketches (.ino) files for comments containing");
            System.out.println("\tDrizzle's markers and outputs the information as JSON or applies");
            System.out.println("\tthe reverse operation to JSON files.\n");

            System.out.printf("Usage:%n");
            System.out.printf("\tjava -jar drizzle-%s.jar --parse <arduino-proj.ino | arduino-proj-directory>%n", implementationVersion);
            System.out.printf("\tjava -jar drizzle-%s.jar --rev-parse <file.json>%n", implementationVersion);
            return;
        }
        if (args.length < 2) {
            System.out.println("Incorrect arguments! Use -h or --help for more information!");
            System.exit(-1);
        }

        if ("--parse".equals(args[0])) {
            parseSketchMarkers(args[1]);
            return;
        } else if ("--rev-parse".equals(args[0])) {
            jsonToSketchMarkers(args[1]);
            return;
        }

        System.err.printf("Unrecognized argument %s%n", args[0]);
        System.exit(-2);
    }

    private static void jsonToSketchMarkers(String jsonFile) {

        try {
            String jsonData = new String(Files.readAllBytes(Paths.get(jsonFile)));

            ProjectSettings projSettings = ProjectSettings.fromJSON(jsonData, new LogProxy());
            if (projSettings == null || !projSettings.containsData()) {
                throw new IllegalStateException("Unable to generate Drizzle markers from the JSON");
            }

            System.out.print(projSettings);

        } catch (IllegalStateException | IOException e) {
            e.printStackTrace(System.err);
            System.exit(-5);
        }
    }

    public static void parseSketchMarkers(String file) {
        File sketch = new File(file);
        if (sketch.exists() && sketch.isDirectory()) {
            sketch = new File(sketch.getPath(), sketch.getName().concat(".ino"));
        }

        if (!sketch.exists()) {
            System.err.printf("Cannot open file %s%n", sketch.toString());
            System.exit(-3);
        }

        SourceExtractor sourceExtractor = new SourceExtractor(null, new LogProxy() {
            @Override
            public PrintStream stderr() {
                // silence it to not polute the CLI output. the exit codes will be still available
                return stdnull();
            }

            @Override
            public PrintStream stdwarn() {
                // silence it to not polute the CLI output. the exit codes will be still available
                return stdnull();
            }
        });

        String source = null;
        try {
            source = new String(Files.readAllBytes(sketch.toPath()));
        } catch (IOException e) {
            e.printStackTrace(System.err);
            System.exit(-4);
        }

        ProjectSettings projectSettings = ProjectSettings.fromSource(sourceExtractor, source);
        System.out.println(ProjectSettings.toJSON(projectSettings));
    }

    public static String version() {
        String implementationVersion = DrizzleCLI.class.getPackage().getImplementationVersion();
        if (TextUtils.isNullOrBlank(implementationVersion)) implementationVersion = "SNAPSHOT";
        return implementationVersion;
    }
}
