package com.github.zhgzhg.drizzle;

import com.github.zhgzhg.drizzle.utils.LogProxy;
import com.github.zhgzhg.drizzle.utils.SourceExtractor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

public class DrizzleCLI {

    public static void main(String[] args) {
        if (args[0].equals("-h") || args[0].equals("--help")) {
            String implementationVersion = DrizzleCLI.class.getPackage().getImplementationVersion();
            if (implementationVersion == null)  implementationVersion = "SNAPSHOT";

            System.out.printf("Drizzle %s CLI Helper%n", implementationVersion);

            System.out.println("\tParses Arduino IDE sketches (.ino) files for comments containing");
            System.out.println("\tDrizzle's markers and outputs the information as JSON\n");

            System.out.printf("Usage:%n");
            System.out.printf("\tjava -jar drizzle-%s.jar --parse <arduino-proj.ino | arduino-proj-directory>%n", implementationVersion);
            return;
        }
        if (args.length < 2) {
            System.out.println("Incorrect arguments! Use -h or --help for more information!");
            System.exit(-1);
        }

        if (!"--parse".equals(args[0])) {
            System.err.printf("Unrecognized argument %s%n", args[0]);
            System.exit(-2);
        }

        File sketch = new File(args[1]);
        if (sketch.exists() && sketch.isDirectory()) {
            sketch = new File(sketch.getPath(), sketch.getName().concat(".ino"));
        }

        if (!sketch.exists()) {
            System.err.printf("Cannot open file %s%n", sketch.toString());
            System.exit(-3);
        }

        SourceExtractor sourceExtractor = new SourceExtractor(new LogProxy());

        String source = null;
        try {
            source = new String(Files.readAllBytes(sketch.toPath()));
        } catch (IOException e) {
            e.printStackTrace(System.err);
            System.exit(-4);
        }

        SourceExtractor.BoardManager boardManager = sourceExtractor.dependentBoardManagerFromMainSketchSource(source);
        SourceExtractor.Board board = sourceExtractor.dependentBoardFromMainSketchSource(source);
        Map<String, String> libraries = sourceExtractor.dependentLibsFromMainSketchSource(source);

        Map<String, Object> result = new LinkedHashMap<>(3);
        result.put("board_manager", boardManager);
        result.put("board", board);
        result.put("libraries", libraries);

        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
        System.out.println(gson.toJson(result));
    }
}
