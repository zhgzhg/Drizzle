package com.github.zhgzhg.drizzle;

import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.source.SourceExtractor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.annotations.SerializedName;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DrizzleCLI {
    public static class BoardSettingsSerializerCustomizer implements JsonSerializer<SourceExtractor.BoardSettings>,
            JsonDeserializer<SourceExtractor.BoardSettings> {

        public static final String BOARD_INFO_HOLDER = "board";
        public static final String CLICKABLE_OPTIONS_HOLDER = "clickable_options";

        public static final String NAME = "name";
        public static final String PLATFORM = "platform";
        public static final String PROVIDER_PACKAGE = "providerPackage";

        @Override
        public JsonElement serialize(final SourceExtractor.BoardSettings src, final Type typeOfSrc,
                final JsonSerializationContext context) {

            JsonObject board = context.serialize(src.board, SourceExtractor.Board.class).getAsJsonObject();

            JsonPrimitive platform = board.getAsJsonPrimitive(PLATFORM);
            if (platform == null || platform.getAsString().isEmpty()) {
                board.add(PLATFORM, new JsonPrimitive("*"));
            }
            JsonPrimitive boardName = board.getAsJsonPrimitive(NAME);
            if (boardName == null || boardName.getAsString().isEmpty()) {
                board.add(NAME, new JsonPrimitive("*"));
            }
            board.remove(PROVIDER_PACKAGE);

            JsonElement clickableOptions = context.serialize(src.clickableOptions, List.class);

            JsonObject result = new JsonObject();
            result.add(BOARD_INFO_HOLDER, board);
            result.add(CLICKABLE_OPTIONS_HOLDER, clickableOptions);

            return result;
        }

        @Override
        public SourceExtractor.BoardSettings deserialize(final JsonElement json, final Type typeOfT,
                final JsonDeserializationContext context) {

            SourceExtractor.Board board = context.deserialize(json.getAsJsonObject().getAsJsonObject(BOARD_INFO_HOLDER), SourceExtractor.Board.class);
            List<List<String>> clickableOptions =
                    context.deserialize(json.getAsJsonObject().getAsJsonArray(CLICKABLE_OPTIONS_HOLDER), List.class);

            SourceExtractor.BoardSettings boardSettings = new SourceExtractor.BoardSettings(board);
            boardSettings.clickableOptions.addAll(clickableOptions);
            return boardSettings;
        }
    }

    public static class ProjectSettings {
        @SerializedName("board_manager")
        private SourceExtractor.BoardManager boardManager;
        private SourceExtractor.Board board;
        @SerializedName("board_settings")
        private List<SourceExtractor.BoardSettings> boardSettings;
        private Map<String, String> libraries;

        public SourceExtractor.BoardManager getBoardManager() {
            return boardManager;
        }

        public void setBoardManager(final SourceExtractor.BoardManager boardManager) {
            this.boardManager = boardManager;
        }

        public SourceExtractor.Board getBoard() {
            return board;
        }

        public void setBoard(final SourceExtractor.Board board) {
            this.board = board;
        }

        public List<SourceExtractor.BoardSettings> getBoardSettings() {
            return boardSettings;
        }

        public void setBoardSettings(final List<SourceExtractor.BoardSettings> boardSettings) {
            this.boardSettings = boardSettings;
        }

        public Map<String, String> getLibraries() {
            return libraries;
        }

        public void setLibraries(final Map<String, String> libraries) {
            this.libraries = libraries;
        }

        public boolean containsData() {
            return boardManager != null || board != null || boardSettings != null || libraries != null;
        }
    }

    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("-h") || args[0].equals("--help"))) {
            String implementationVersion = DrizzleCLI.class.getPackage().getImplementationVersion();
            if (implementationVersion == null) implementationVersion = "SNAPSHOT";

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
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(SourceExtractor.BoardSettings.class, new BoardSettingsSerializerCustomizer())
                .create();
        try {
            ProjectSettings projSettings = gson.fromJson(new FileReader(jsonFile), ProjectSettings.class);
            if (projSettings == null || !projSettings.containsData()) {
                throw new IllegalStateException("Unable to generate Drizzle markers from the JSON");
            }

            if (projSettings.boardManager != null) {
                System.out.printf("%s %s::%s%s%n", SourceExtractor.BOARDMANAGER_MARKER, projSettings.boardManager.platform,
                        projSettings.boardManager.version,
                        (projSettings.boardManager.url != null && !projSettings.boardManager.url.isEmpty() ?
                                "::" + projSettings.boardManager.url : "")
                );
            }

            if (projSettings.board != null) {
                String providerPackage = projSettings.board.providerPackage;
                if (providerPackage == null || providerPackage.isEmpty()) providerPackage = projSettings.board.platform;
                System.out.printf("%s %s::%s::%s%n", SourceExtractor.BOARDNAME_MARKER, providerPackage, projSettings.board.platform,
                        projSettings.board.name);
            }

            if (projSettings.boardSettings != null) {
                projSettings.boardSettings.forEach(bs -> {
                    String opts = bs.clickableOptions.stream()
                            .map(ls -> String.join("->", ls))
                            .collect(Collectors.joining("||"));

                    String platform = bs.board.platform;
                    if (platform == null || platform.isEmpty()) platform = "*";

                    String name = bs.board.name;
                    if (name == null || name.isEmpty()) name = "*";

                    System.out.printf("%s %s::%s::%s%n", SourceExtractor.BOARDSETTINGS_MARKER, platform, name, opts);
                });
            }

            if (projSettings.libraries != null) {
                projSettings.libraries.forEach((k, v) ->
                    System.out.printf("%s %s::%s%n", SourceExtractor.DEPENDSON_MARKER, k, v)
                );
            }

        } catch (IllegalStateException | FileNotFoundException e) {
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

        SourceExtractor sourceExtractor = new SourceExtractor(new LogProxy() {
            @Override
            public void cliError(final String format, final Object... params) {
                // silence it to not polute the CLI output. the exit codes will be still available
            }

            @Override
            public void cliErrorln() {
                // silence it to not polute the CLI output. the exit codes will be still available
            }

            @Override
            public void cliErrorln(final String msg) {
                // silence it to not polute the CLI output. the exit codes will be still available
            }

            @Override
            public void cliErrorln(final Throwable t) {
                // silence it to not polute the CLI output. the exit codes will be still available
            }
        });

        String source = null;
        try {
            source = new String(Files.readAllBytes(sketch.toPath()));
        } catch (IOException e) {
            e.printStackTrace(System.err);
            System.exit(-4);
        }

        SourceExtractor.BoardManager boardManager = sourceExtractor.dependentBoardManagerFromMainSketchSource(source);
        SourceExtractor.Board board = sourceExtractor.dependentBoardFromMainSketchSource(source);
        List<SourceExtractor.BoardSettings> boardSettings = sourceExtractor.dependentBoardClickableSettingsFromMainSketchSource(source);
        Map<String, String> libraries = sourceExtractor.dependentLibsFromMainSketchSource(source);

        ProjectSettings projectSettings = new ProjectSettings();
        projectSettings.setBoardManager(boardManager);
        projectSettings.setBoard(board);
        projectSettings.setBoardSettings(boardSettings);
        projectSettings.setLibraries(libraries);

        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting()
                .registerTypeAdapter(SourceExtractor.BoardSettings.class, new BoardSettingsSerializerCustomizer())
                .create();
        System.out.println(gson.toJson(projectSettings));
    }
}
