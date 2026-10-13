package com.github.zhgzhg;

import com.github.zhgzhg.drizzle.utils.json.BoardSettingsSerializerCustomizer;
import com.github.zhgzhg.drizzle.utils.json.MapOfArduinoToolsSerializerCustomizer;
import com.github.zhgzhg.drizzle.utils.json.MapOfDependentLibrariesSerializerCustomizer;
import com.github.zhgzhg.drizzle.utils.json.PreferencesSerializerCustomizer;
import com.github.zhgzhg.drizzle.utils.json.ProjectSettings;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.source.SourceExtractor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class DrizzleParsingTest {
    private final LogProxy strictLogProxy = new LogProxy() {
        @Override
        public PrintStream stderr() {
            fail("CLI Error printed");
            return super.stderr();
        }

        @Override
        public PrintStream stdwarn() {
            fail("CLI Warning printed");
            return super.stdwarn();
        }

        @Override
        public void uiError(final String format, final Object... params) {
            fail("UI Error printed");
        }

        @Override
        public void uiError(final Throwable t) {
            fail("UI Error printed");
        }

        @Override
        public void uiWarn(final String format, final Object... params) {
            fail("UI Warning printed");
        }
    };

    private static String loadWholeTextResource(String path) throws IOException {
        try (InputStream sketch = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
                InputStreamReader isr = new InputStreamReader(sketch);
                BufferedReader br = new BufferedReader(isr)) {

            String lineDelim = "\n";

            return br.lines().map(line -> line.replace("\r\n", lineDelim))
                    .collect(Collectors.joining(lineDelim));
        }
    }

    private static ProjectSettings createProjectSettings(SourceExtractor sourceExtractor, String source) {
        SourceExtractor.BoardManager boardManager = sourceExtractor.dependentBoardManagerFromMainSketchSource(source);
        SourceExtractor.Board board = sourceExtractor.dependentBoardFromMainSketchSource(source);
        List<SourceExtractor.BoardSettings> boardSettings = sourceExtractor.dependentBoardClickableSettingsFromMainSketchSource(source);
        Map<String, SourceExtractor.DependentLibrary> libraries = sourceExtractor.dependentLibsFromMainSketchSource(source);
        List<SourceExtractor.Preferences> preferences = sourceExtractor.dependentPreferencesFromMainSketchSource(source);
        List<SourceExtractor.ArduinoTool> arduinoTools = sourceExtractor.arduinoToolsFromMainSketchSource(source);
        Map<String, SourceExtractor.ArduinoTool> arduinoToolMap = null;
        if (arduinoTools != null && !arduinoTools.isEmpty()) {
            arduinoToolMap = new LinkedHashMap<>();
            for (SourceExtractor.ArduinoTool at : arduinoTools) {
                arduinoToolMap.put(at.name, at);
            }
        }

        ProjectSettings projectSettings = new ProjectSettings();
        projectSettings.setBoardManager(boardManager);
        projectSettings.setBoard(board);
        projectSettings.setBoardSettings(boardSettings);
        projectSettings.setLibraries(libraries);
        projectSettings.setPreferences(preferences);
        projectSettings.setArduinoIdeTools(arduinoToolMap);
        return projectSettings;
    }

    @Test
    void sourceMarkerParserTest() throws IOException {
        String source = loadWholeTextResource("sample_sketch.ino");
        String source2 = loadWholeTextResource("sample_sketch2.ino");
        SourceExtractor sourceExtractor = new SourceExtractor(null, strictLogProxy);

        ProjectSettings projectSettings = createProjectSettings(sourceExtractor, source);
        ProjectSettings projectSettings2 = createProjectSettings(sourceExtractor, source2);

        Type dependentLibraryContainerType = new TypeToken<Map<String, SourceExtractor.DependentLibrary>>() { }.getType();
        Type ardToolsContainerType = new TypeToken<Map<String, SourceExtractor.ArduinoTool>>() { }.getType();

        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().serializeNulls()
                .registerTypeAdapter(SourceExtractor.BoardSettings.class, new BoardSettingsSerializerCustomizer())
                .registerTypeAdapter(dependentLibraryContainerType, new MapOfDependentLibrariesSerializerCustomizer())
                .registerTypeAdapter(SourceExtractor.Preferences.class, new PreferencesSerializerCustomizer())
                .registerTypeAdapter(ardToolsContainerType, new MapOfArduinoToolsSerializerCustomizer())
                .create();

        assertEquals(loadWholeTextResource("sample_sketch_parsed.json"), gson.toJson(projectSettings));
        assertEquals(loadWholeTextResource("sample_sketch_parsed2.json"), gson.toJson(projectSettings2));
    }

    @Test
    void wildcardBoardSettingsTurnIntoJson() {
        String source = "/*\n * @BoardSettings *::*::Upload Speed->115200\n */\nvoid setup() {}\nvoid loop() {}\n";
        ProjectSettings projectSettings = createProjectSettings(new SourceExtractor(null, strictLogProxy), source);

        String json = ProjectSettings.toJSON(projectSettings);
        assertTrue(json.contains("\"platform\": \"*\""), json);
        assertTrue(json.contains("\"name\": \"*\""), json);
        assertTrue(json.contains("\"Upload Speed\""), json);
    }

    @Test
    void keepsEveryBoardSettingsAndPreferencesMarker() {
        String source = "/*\n"
                + " * @BoardSettings esp32::esp32wrover::Flash Frequency->80MHz\n"
                + " * @BoardSettings esp32::*::Flash Frequency->40MHz||PSRAM->Disabled\n"
                + " * @BoardSettings esp32::*::Upload Speed->921600\n"
                + " * @Preferences esp32::esp32::esp32wrover::compiler.cpp.extra_flags=-DA\n"
                + " * @Preferences esp32::esp32::*::compiler.cpp.extra_flags=-DB||compiler.c.extra_flags=-DC\n"
                + " */\nvoid setup() {}\nvoid loop() {}\n";
        SourceExtractor sourceExtractor = new SourceExtractor(null, strictLogProxy);

        List<SourceExtractor.BoardSettings> boardSettings = sourceExtractor.dependentBoardClickableSettingsFromMainSketchSource(source);
        assertEquals(3, boardSettings.size(), boardSettings.toString());
        assertEquals("esp32wrover", boardSettings.get(0).board.name);
        assertEquals(Arrays.asList(Arrays.asList("Flash Frequency", "40MHz"), Arrays.asList("PSRAM", "Disabled")),
                boardSettings.get(1).clickableOptions);
        assertEquals(Collections.singletonList(Arrays.asList("Upload Speed", "921600")), boardSettings.get(2).clickableOptions);

        List<SourceExtractor.Preferences> preferences = sourceExtractor.dependentPreferencesFromMainSketchSource(source);
        assertEquals(2, preferences.size(), preferences.toString());

        Map<String, String> forWrover = SourceExtractor.Preferences.applicable(preferences, "esp32", "esp32", "esp32wrover", "ESP32 Wrover Module");
        assertEquals("-DA", forWrover.get("compiler.cpp.extra_flags"), "the first, most specific marker wins");
        assertEquals("-DC", forWrover.get("compiler.c.extra_flags"), "later markers fill in the rest");

        Map<String, String> forDevModule = SourceExtractor.Preferences.applicable(preferences, "esp32", "esp32", "esp32", "ESP32 Dev Module");
        assertEquals("-DB", forDevModule.get("compiler.cpp.extra_flags"));
    }

    @Test
    void wildcardPreferencesMatchEveryBoard() {
        SourceExtractor.Preferences anyBoard = new SourceExtractor.Preferences(new SourceExtractor.Board("*", "*", "*"));
        SourceExtractor.Preferences anyEsp32Board = new SourceExtractor.Preferences(new SourceExtractor.Board("esp32", "*", "*"));
        SourceExtractor.Preferences anyAvrBoard = new SourceExtractor.Preferences(new SourceExtractor.Board("*", "avr", "*"));

        assertTrue(anyBoard.suitsRequirements("esp32", "esp32", "esp32"));
        assertTrue(anyEsp32Board.suitsRequirements("esp32", "esp32", "esp32"));
        assertFalse(anyEsp32Board.suitsRequirements("arduino", "avr", "uno"));
        assertTrue(anyAvrBoard.suitsRequirements("arduino", "avr", "uno"));
        assertFalse(anyAvrBoard.suitsRequirements("esp32", "esp32", "esp32"));
    }

    @Test
    void jsonParserTest() throws IOException {

        Type dependentLibraryContainerType = new TypeToken<Map<String, SourceExtractor.DependentLibrary>>() { }.getType();
        Type ardToolsContainerType = new TypeToken<Map<String, SourceExtractor.ArduinoTool>>() { }.getType();
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(SourceExtractor.BoardSettings.class, new BoardSettingsSerializerCustomizer())
                .registerTypeAdapter(dependentLibraryContainerType, new MapOfDependentLibrariesSerializerCustomizer())
                .registerTypeAdapter(SourceExtractor.Preferences.class, new PreferencesSerializerCustomizer())
                .registerTypeAdapter(ardToolsContainerType, new MapOfArduinoToolsSerializerCustomizer())
                .create();

        String json = loadWholeTextResource("sample_sketch_parsed.json");
        ProjectSettings projSettings = gson.fromJson(json, ProjectSettings.class);
        ProjectSettings projSettingsTemplate = createProjectSettings(
                new SourceExtractor(null, this.strictLogProxy), loadWholeTextResource("sample_sketch.ino"));

        assertEquals(projSettingsTemplate.getBoardManager().toString(), projSettings.getBoardManager().toString());
        assertEquals(projSettingsTemplate.getBoard().toString(), projSettings.getBoard().toString());
        assertEquals(projSettingsTemplate.getBoardSettings().toString(), projSettings.getBoardSettings().toString());
        assertEquals(projSettingsTemplate.getLibraries().toString(), projSettings.getLibraries().toString());
        assertEquals(projSettingsTemplate.getArduinoIdeTools().toString(), projSettings.getArduinoIdeTools().toString());

        json = loadWholeTextResource("sample_sketch_parsed2.json");
        projSettings = gson.fromJson(json, ProjectSettings.class);
        projSettingsTemplate = createProjectSettings(
                new SourceExtractor(null, this.strictLogProxy), loadWholeTextResource("sample_sketch2.ino"));

        assertEquals(projSettingsTemplate.getBoardManager().toString(), projSettings.getBoardManager().toString());
        assertEquals(projSettingsTemplate.getBoard().toString(), projSettings.getBoard().toString());
        assertEquals(projSettingsTemplate.getBoardSettings().toString(), projSettings.getBoardSettings().toString());
        assertEquals(projSettingsTemplate.getLibraries().toString(), projSettings.getLibraries().toString());
        assertEquals(projSettingsTemplate.getArduinoIdeTools().toString(), projSettings.getArduinoIdeTools().toString());
    }
}
