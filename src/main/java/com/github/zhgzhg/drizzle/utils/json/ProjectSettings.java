package com.github.zhgzhg.drizzle.utils.json;

import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.source.SourceExtractor;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ProjectSettings {
    @SerializedName("board_manager")
    private SourceExtractor.BoardManager boardManager;
    private SourceExtractor.Board board;
    @SerializedName("board_settings")
    private List<SourceExtractor.BoardSettings> boardSettings;
    private Map<String, String> libraries;
    @SerializedName("arduino_ide_tools")
    private Map<String, SourceExtractor.ArduinoTool> arduinoIdeTools;

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

    public Map<String, SourceExtractor.ArduinoTool> getArduinoIdeTools() {
        return arduinoIdeTools;
    }

    public void setArduinoIdeTools(final Map<String, SourceExtractor.ArduinoTool> arduinoIdeTools) {
        this.arduinoIdeTools = arduinoIdeTools;
    }

    public boolean containsData() {
        return boardManager != null || board != null || boardSettings != null || libraries != null || arduinoIdeTools != null;
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        if (this.getBoardManager() != null) {
            String boardManagerStr = String.format("%s %s::%s%s%n", SourceExtractor.BOARDMANAGER_MARKER, this.getBoardManager().platform,
                    this.getBoardManager().version,
                    (TextUtils.isNotNullOrBlank(this.getBoardManager().url) ? "::" + this.getBoardManager().url : ""));
            sb.append(boardManagerStr);
        }

        if (this.getBoard() != null) {
            sb.append(String.format("%s ", SourceExtractor.BOARDNAME_MARKER));

            String providerPackage = this.getBoard().providerPackage;
            if (TextUtils.isNotNullOrBlank(providerPackage)) {
                sb.append(String.format("%s::", providerPackage));
            }

            sb.append(String.format("%s::%s%n", this.getBoard().platform, this.getBoard().name));
        }

        if (this.getBoardSettings() != null) {
            this.getBoardSettings().forEach(bs -> {
                String opts = bs.clickableOptions.stream()
                        .map(ls -> String.join("->", ls))
                        .collect(Collectors.joining("||"));

                String platform = bs.board.platform;
                if (platform == null || platform.isEmpty()) platform = "*";

                String name = bs.board.name;
                if (name == null || name.isEmpty()) name = "*";

                String boardSettingsStr = String.format("%s %s::%s::%s%n", SourceExtractor.BOARDSETTINGS_MARKER, platform, name, opts);
                sb.append(boardSettingsStr);
            });
        }

        if (this.getLibraries() != null) {
            this.getLibraries().forEach((k, v) ->
                    sb.append(String.format("%s %s::%s%n", SourceExtractor.DEPENDSON_MARKER, k, v))
            );
        }

        if (this.getArduinoIdeTools() != null) {
            this.getArduinoIdeTools().forEach((k, at) ->
                    sb.append(String.format("%s %s::%s::%s%n", SourceExtractor.ARDUINOTOOL_MARKER, at.name, at.version, at.url))
            );
        }

        return sb.toString();
    }

    public static String toJSON(ProjectSettings projectSettings) {
        Type ardToolsContainerType = new TypeToken<Map<String, SourceExtractor.ArduinoTool>>() { }.getType();

        Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().setPrettyPrinting()
                .registerTypeAdapter(SourceExtractor.BoardSettings.class, new BoardSettingsSerializerCustomizer())
                .registerTypeAdapter(ardToolsContainerType, new MapOfArduinoToolsSerializerCustomizer())
                .create();
        return gson.toJson(projectSettings);
    }

    public static ProjectSettings fromJSON(String json, LogProxy logger) {
        Type ardToolsContainerType = new TypeToken<Map<String, SourceExtractor.ArduinoTool>>() { }.getType();

        Gson gson = new GsonBuilder()
                .registerTypeAdapter(SourceExtractor.BoardSettings.class, new BoardSettingsSerializerCustomizer())
                .registerTypeAdapter(ardToolsContainerType, new MapOfArduinoToolsSerializerCustomizer())
                .create();
        try {
            return gson.fromJson(json, ProjectSettings.class);
        } catch (Exception ex) {
            logger.cliErrorln(ex);
        }

        return null;
    }

    public static ProjectSettings fromSource(SourceExtractor sourceExtractor, String source) {
        SourceExtractor.BoardManager boardManager = sourceExtractor.dependentBoardManagerFromMainSketchSource(source);
        SourceExtractor.Board board = sourceExtractor.dependentBoardFromMainSketchSource(source);
        List<SourceExtractor.BoardSettings> boardSettings = sourceExtractor.dependentBoardClickableSettingsFromMainSketchSource(source);
        Map<String, String> libraries = sourceExtractor.dependentLibsFromMainSketchSource(source);
        List<SourceExtractor.ArduinoTool> arduinoTools = sourceExtractor.arduinoToolsFromMainSketchSource(source);

        Map<String, SourceExtractor.ArduinoTool> arduinoToolsMap = null;
        if (arduinoTools != null && !arduinoTools.isEmpty()) {
            arduinoToolsMap = new LinkedHashMap<>();
            for (SourceExtractor.ArduinoTool at : arduinoTools) {
                arduinoToolsMap.put(at.name, at);
            }
        }

        ProjectSettings projectSettings = new ProjectSettings();
        projectSettings.setBoardManager(boardManager);
        projectSettings.setBoard(board);
        projectSettings.setBoardSettings(boardSettings);
        projectSettings.setLibraries(libraries);
        projectSettings.setArduinoIdeTools(arduinoToolsMap);

        return projectSettings;
    }
}
