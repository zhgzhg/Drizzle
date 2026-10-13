package com.github.zhgzhg;

import com.github.zhgzhg.drizzle.DrizzleCLI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DrizzleCLITest {

    @TempDir
    Path tmp;

    private Path sketchFolder;

    @BeforeEach
    void createSketch() throws Exception {
        sketchFolder = Files.createDirectory(tmp.resolve("blink"));
        Files.write(sketchFolder.resolve("blink.ino"),
                "/*\n * @DependsOn FromMarkers::1.0.0\n */\nvoid setup() {}\nvoid loop() {}\n".getBytes(StandardCharsets.UTF_8));
    }

    private void addDrizzleJson() throws Exception {
        Files.write(sketchFolder.resolve("drizzle.json"),
                "{ \"libraries\": { \"FromDrizzleJson\": { \"version\": \"^2.0.0\" } } }".getBytes(StandardCharsets.UTF_8));
    }

    private static String parse(final Path path) {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        try {
            DrizzleCLI.parseSketchMarkers(path.toString());
        } finally {
            System.setOut(originalOut);
        }
        return new String(out.toByteArray());
    }

    @Test
    void parsesTheMarkersOfASketchFolder() {
        String json = parse(sketchFolder);
        assertTrue(json.contains("\"FromMarkers\""), json);
    }

    @Test
    void prefersTheDrizzleJsonOfASketchFolderLikeTheIde() throws Exception {
        addDrizzleJson();

        String json = parse(sketchFolder);
        assertTrue(json.contains("\"FromDrizzleJson\""), json);
        assertTrue(json.contains("\"arduinoCliFmt\": \"FromDrizzleJson@2.0.0\""), "same form as parsed markers: " + json);
        assertFalse(json.contains("FromMarkers"), "the markers are ignored, as in the IDE: " + json);
    }

    @Test
    void parsesTheMarkersOfAGivenFileEvenNextToADrizzleJson() throws Exception {
        addDrizzleJson();

        String json = parse(sketchFolder.resolve("blink.ino"));
        assertTrue(json.contains("\"FromMarkers\""), json);
        assertFalse(json.contains("FromDrizzleJson"), json);
    }
}
