package com.github.zhgzhg.drizzle.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts GCC-style diagnostics ({@code file:line:column: severity: message}) from compiler output.
 * <p>
 * Arduino IDE rewrites the error lines of files whose name matches a sketch tab to just that name (for .ino files
 * without the extension), e.g. {@code blink:12:3: error: ...}. When the compiler's preceding context line
 * ({@code <path>: In function '...':}) names a file with that name, its path is used instead, which also tells apart
 * a library header that happens to share a sketch tab's name.
 */
public final class CompilerDiagnostics {
    private CompilerDiagnostics() { }

    static final int MAX_DIAGNOSTICS = 100;

    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "^(.+?):(\\d+):(?:(\\d+):)?\\s*(fatal error|error|warning|note):\\s*(.*)$");
    private static final Pattern CONTEXT = Pattern.compile("^(.+?):\\s+(?:In |At ).*:$");
    private static final Pattern ABSOLUTE = Pattern.compile("^(/|\\\\\\\\|[A-Za-z]:[\\\\/]).*");

    public static List<Map<String, Object>> parse(final String output) {
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String contextFile = null;

        for (String rawLine : output.split("\\r?\\n")) {
            String line = rawLine.trim();
            Matcher m = DIAGNOSTIC.matcher(line);
            if (!m.matches()) {
                Matcher context = CONTEXT.matcher(line);
                if (context.matches()) contextFile = context.group(1);
                continue;
            }
            if (!seen.add(m.group(0))) continue;

            String file = m.group(1);
            if (!ABSOLUTE.matcher(file).matches() && contextFile != null && namesFile(file, contextFile)) {
                file = contextFile;
            }

            Map<String, Object> diagnostic = new LinkedHashMap<>();
            diagnostic.put("file", file);
            diagnostic.put("line", Integer.parseInt(m.group(2)));
            if (m.group(3) != null) diagnostic.put("column", Integer.parseInt(m.group(3)));
            diagnostic.put("severity", m.group(4));
            diagnostic.put("message", m.group(5));
            diagnostics.add(diagnostic);

            if (diagnostics.size() >= MAX_DIAGNOSTICS) break;
        }
        return diagnostics;
    }

    /**
     * @return whether the file name (or, for .ino files, the name without extension) of the path is {@code name}
     */
    private static boolean namesFile(final String name, final String path) {
        String fileName = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        return fileName.equals(name) || (fileName.endsWith(".ino") && fileName.equals(name + ".ino"));
    }
}
