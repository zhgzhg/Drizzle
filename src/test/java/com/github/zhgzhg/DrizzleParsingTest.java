package com.github.zhgzhg;

import com.github.zhgzhg.drizzle.DrizzleCLI;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.source.SourceExtractor;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class DrizzleParsingTest {
    private LogProxy strictLogProxy = new LogProxy() {
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

    private static DrizzleCLI.ProjectSettings createProjectSettings(SourceExtractor sourceExtractor, String source) {
        SourceExtractor.BoardManager boardManager = sourceExtractor.dependentBoardManagerFromMainSketchSource(source);
        SourceExtractor.Board board = sourceExtractor.dependentBoardFromMainSketchSource(source);
        List<SourceExtractor.BoardSettings> boardSettings = sourceExtractor.dependentBoardClickableSettingsFromMainSketchSource(source);
        Map<String, String> libraries = sourceExtractor.dependentLibsFromMainSketchSource(source);

        DrizzleCLI.ProjectSettings projectSettings = new DrizzleCLI.ProjectSettings();
        projectSettings.setBoardManager(boardManager);
        projectSettings.setBoard(board);
        projectSettings.setBoardSettings(boardSettings);
        projectSettings.setLibraries(libraries);
        return projectSettings;
    }

    @Test
    public void sourceMarkerParserTest() throws IOException {
        String source = loadWholeTextResource("sample_sketch.ino");
        String source2 = loadWholeTextResource("sample_sketch2.ino");
        SourceExtractor sourceExtractor = new SourceExtractor(strictLogProxy);

        DrizzleCLI.ProjectSettings projectSettings = createProjectSettings(sourceExtractor, source);
        DrizzleCLI.ProjectSettings projectSettings2 = createProjectSettings(sourceExtractor, source2);

        Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().serializeNulls()
                .registerTypeAdapter(SourceExtractor.BoardSettings.class, new DrizzleCLI.BoardSettingsSerializerCustomizer())
                .create();

        assertEquals(loadWholeTextResource("sample_sketch_parsed.json"), gson.toJson(projectSettings));
        assertEquals(loadWholeTextResource("sample_sketch_parsed2.json"), gson.toJson(projectSettings2));
    }

    @Test
    public void jsonParserTest() throws IOException {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(SourceExtractor.BoardSettings.class, new DrizzleCLI.BoardSettingsSerializerCustomizer())
                .create();

        String json = loadWholeTextResource("sample_sketch_parsed.json");
        DrizzleCLI.ProjectSettings projSettings = gson.fromJson(json, DrizzleCLI.ProjectSettings.class);
        DrizzleCLI.ProjectSettings projSettingsTemplate = createProjectSettings(
                new SourceExtractor(this.strictLogProxy), loadWholeTextResource("sample_sketch.ino"));

        assertEquals(projSettingsTemplate.getBoardManager().toString(), projSettings.getBoardManager().toString());
        assertEquals(projSettingsTemplate.getBoard().toString(), projSettings.getBoard().toString());
        assertEquals(projSettingsTemplate.getBoardSettings().toString(), projSettings.getBoardSettings().toString());
        assertEquals(projSettingsTemplate.getLibraries().toString(), projSettings.getLibraries().toString());

        json = loadWholeTextResource("sample_sketch_parsed2.json");
        projSettings = gson.fromJson(json, DrizzleCLI.ProjectSettings.class);
        projSettingsTemplate = createProjectSettings(
                new SourceExtractor(this.strictLogProxy), loadWholeTextResource("sample_sketch2.ino"));

        assertEquals(projSettingsTemplate.getBoardManager().toString(), projSettings.getBoardManager().toString());
        assertEquals(projSettingsTemplate.getBoard().toString(), projSettings.getBoard().toString());
        assertEquals(projSettingsTemplate.getBoardSettings().toString(), projSettings.getBoardSettings().toString());
        assertEquals(projSettingsTemplate.getLibraries().toString(), projSettings.getLibraries().toString());
    }
}
