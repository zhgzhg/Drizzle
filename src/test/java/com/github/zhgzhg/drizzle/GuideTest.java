package com.github.zhgzhg.drizzle;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuideTest {
    private static final Pattern TOPIC_MENTION = Pattern.compile("--guide ([a-z][a-z-]*)`");

    private String out;
    private String err;

    private int guide(final String... args) {
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream o = new PrintStream(outBytes, true); PrintStream e = new PrintStream(errBytes, true)) {
            exitCode = Guide.print(args, o, e);
        }
        out = new String(outBytes.toByteArray(), StandardCharsets.UTF_8);
        err = new String(errBytes.toByteArray(), StandardCharsets.UTF_8);
        return exitCode;
    }

    private static List<String> allTexts() throws Exception {
        List<String> texts = new ArrayList<>();
        texts.add(Guide.text(null));
        for (String topic : Guide.TOPICS) {
            texts.add(Guide.text(topic));
        }
        return texts;
    }

    @Test
    void printsTheInstructionsOfThisVersion() {
        assertEquals(0, guide());
        assertTrue(out.startsWith("# Drizzle remote control"), out);
        assertTrue(out.contains("version " + DrizzleCLI.version() + "."), out);
        assertFalse(out.contains("{{"), "no placeholder left");
    }

    @Test
    void printsTheMarkerReference() {
        assertEquals(0, guide("markers"));
        assertTrue(out.startsWith("# Drizzle markers and drizzle.json"), out);
    }

    @Test
    void rejectsUnknownTopics() {
        assertEquals(2, guide("nonsense"));
        assertTrue(err.contains("Topics: markers"), err);
        assertEquals("", out);

        assertEquals(2, guide("markers", "more"));
    }

    @Test
    void staysAsciiSoAnyConsolePrintsItIntact() throws Exception {
        for (String text : allTexts()) {
            for (int i = 0; i < text.length(); ++i) {
                char c = text.charAt(i);
                assertTrue(c < 0x80, "non-ASCII '" + c + "' in: " + text.substring(Math.max(0, i - 40), i + 1));
            }
        }
    }

    @Test
    void pointsOnlyToTopicsThatExist() throws Exception {
        List<String> texts = allTexts();
        // the skill installed into agents only points here
        texts.add(new String(Files.readAllBytes(Paths.get("skills", "drizzle-arduino", "SKILL.md")), StandardCharsets.UTF_8));

        for (String text : texts) {
            Matcher mention = TOPIC_MENTION.matcher(text);
            while (mention.find()) {
                assertTrue(Guide.TOPICS.contains(mention.group(1)), "unknown topic " + mention.group(1));
            }
            assertFalse(text.contains("references/"), "no files left to read next to the skill");
        }
    }
}
