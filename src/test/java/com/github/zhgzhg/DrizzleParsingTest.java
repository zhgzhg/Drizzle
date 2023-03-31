package com.github.zhgzhg;

import com.github.zhgzhg.drizzle.utils.json.BoardSettingsSerializerCustomizer;
import com.github.zhgzhg.drizzle.utils.json.MapOfArduinoToolsSerializerCustomizer;
import com.github.zhgzhg.drizzle.utils.json.MapOfDependentLibrariesSerializerCustomizer;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                .registerTypeAdapter(ardToolsContainerType, new MapOfArduinoToolsSerializerCustomizer())
                .create();

        assertEquals(loadWholeTextResource("sample_sketch_parsed.json"), gson.toJson(projectSettings));
        assertEquals(loadWholeTextResource("sample_sketch_parsed2.json"), gson.toJson(projectSettings2));
    }

    @Test
    void jsonParserTest() throws IOException {

        Type dependentLibraryContainerType = new TypeToken<Map<String, SourceExtractor.DependentLibrary>>() { }.getType();
        Type ardToolsContainerType = new TypeToken<Map<String, SourceExtractor.ArduinoTool>>() { }.getType();
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(SourceExtractor.BoardSettings.class, new BoardSettingsSerializerCustomizer())
                .registerTypeAdapter(dependentLibraryContainerType, new MapOfDependentLibrariesSerializerCustomizer())
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
