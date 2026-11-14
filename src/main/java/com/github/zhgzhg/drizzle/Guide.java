package com.github.zhgzhg.drizzle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * {@code drizzle --guide [topic]}: the instructions for LLM agents, bundled with Drizzle so they always match the
 * installed version, however it got updated. The drizzle-arduino skill only points agents here.
 * <p>
 * The texts are ASCII only, so every console code page prints them intact.
 */
public final class Guide {
    public static final List<String> TOPICS = Collections.singletonList("markers");

    private static final String MAIN = "guide";
    private static final String RESOURCE_FOLDER = "/com/github/zhgzhg/drizzle/guide/";
    private static final String VERSION_PLACEHOLDER = "{{version}}";

    private Guide() { }

    /**
     * @param args the arguments after {@code --guide}: none, or a topic
     * @return the exit code
     */
    public static int print(final String[] args, final PrintStream out, final PrintStream err) {
        if (args.length > 1) {
            err.printf("Usage: drizzle --guide [%s]%n", String.join("|", TOPICS));
            return 2;
        }

        String topic = args.length == 1 ? args[0] : null;
        if (topic != null && !TOPICS.contains(topic)) {
            err.printf("Unknown guide topic: %s. Topics: %s%n", topic, String.join(", ", TOPICS));
            return 2;
        }

        try {
            out.print(text(topic));
            out.flush();
            return 0;
        } catch (IOException e) {
            err.printf("Can't read the guide: %s%n", e.getMessage());
            return 1;
        }
    }

    /**
     * @param topic one of {@link #TOPICS}, or null for the guide itself
     */
    static String text(final String topic) throws IOException {
        String resource = RESOURCE_FOLDER + (topic == null ? MAIN : topic) + ".md";
        try (InputStream in = Guide.class.getResourceAsStream(resource)) {
            if (in == null) throw new IOException("Missing " + resource);

            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                text.write(chunk, 0, read);
            }
            return new String(text.toByteArray(), StandardCharsets.UTF_8).replace(VERSION_PLACEHOLDER, DrizzleCLI.version());
        }
    }
}
