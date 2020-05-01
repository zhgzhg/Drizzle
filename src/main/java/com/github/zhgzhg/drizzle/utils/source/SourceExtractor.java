package com.github.zhgzhg.drizzle.utils.source;

import com.github.zhgzhg.drizzle.parser.CPP14Lexer;
import com.github.zhgzhg.drizzle.parser.CPP14Parser;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import processing.app.Editor;
import processing.app.SketchFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class SourceExtractor {
    public static final String ERR_DETERMINING_PRIMARY_SKETCH_FILE = "Failed determining the primary sketch file!";

    public static final String DEPENDSON_MARKER = "@DependsOn";
    public static final String BOARDMANAGER_MARKER = "@BoardManager";
    public static final String BOARDNAME_MARKER = "@Board";

    public static final String PLATFORM_GROUP = "platform";
    public static final String LIB_GROUP = "lib";
    public static final String VER_GROUP = "ver";
    public static final String URL_GROUP = "url";
    public static final String BOARD_GROUP = "board";


    public static final Pattern NEW_LINE_SPLITTER = Pattern.compile("\\R+");

    public static final Pattern BOARD_MANAGER = Pattern.compile("^[^@]*"
            + BOARDMANAGER_MARKER + "\\s+(?<" + PLATFORM_GROUP + ">[^:]+)::(?<" + VER_GROUP + ">[^:]+)(::(?<" + URL_GROUP + ">.*))?$");

    public static final Pattern BOARD =
            Pattern.compile("^[^@]*" + BOARDNAME_MARKER + "\\s+(?<" + PLATFORM_GROUP + ">[^:]+)::(?<" + BOARD_GROUP + ">.+)$");

    public static final Pattern DEPENDS_ON_LIB_VERSION =
            Pattern.compile("^[^@]*" + DEPENDSON_MARKER + "\\s+(?<" + LIB_GROUP + ">[^:]+)::(?<" + VER_GROUP + ">.+)$");
    private LogProxy logProxy;

    public static class BoardManager {
        public final String platform;
        public final String version;
        public final String url;

        public BoardManager(String platform, String version, String url) {
            this.platform = platform;
            this.version = version;
            this.url = url;
        }

        @Override
        public String toString() {
            return "BoardManager{" + "platform='" + platform + '\'' + ", version='" + version + '\'' + ", url='" + url + '\'' + '}';
        }
    }

    public static class Board {
        public final String platform;
        public final String name;

        public Board(final String platform, final String name) {
            this.platform = platform;
            this.name = name;
        }

        @Override
        public String toString() {
            return "Board{" + "platform='" + platform + '\'' + ", name='" + name + '\'' + '}';
        }
    }

    public SourceExtractor(LogProxy logProxy) {
        this.logProxy = logProxy;
    }

    public static String loadSourceFromPrimarSketch(Editor editor) throws IOException {
        if (editor != null) {
            SketchFile primaryFile = editor.getSketch().getPrimaryFile();
            if (primaryFile != null) {
                return primaryFile.load();
            } else {
                editor.statusError(ERR_DETERMINING_PRIMARY_SKETCH_FILE);
            }
        }
        return null;
    }

    public Board dependentBoardFromMainSketchSource(String source) {

        try {
            Map<String, String> boardPlatformAndName = extractMarkersKeyValue(
                    extractAllCommentsFromSource(source), BOARD, BOARDNAME_MARKER, PLATFORM_GROUP, BOARD_GROUP);

            Board result = null;
            for (Iterator<Map.Entry<String, String>> it = boardPlatformAndName.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, String> boardCandidate = it.next();

                if (result == null && boardCandidate.getValue() != null && !boardCandidate.getValue().isEmpty()) {
                    result = new Board(boardCandidate.getKey(), boardCandidate.getValue());
                } else {
                    this.logProxy.cliError("Ignoring additional %s %s::%s%n", BOARDNAME_MARKER, boardCandidate.getKey(),
                            boardCandidate.getValue());
                }
            }

            return result;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    public BoardManager dependentBoardManagerFromMainSketchSource(String source) {

        try {
            Map<String, Map<String, String>> platformBoardUrl = extractMarkersKeyAndParams(extractAllCommentsFromSource(source),
                    BOARD_MANAGER, BOARDMANAGER_MARKER, PLATFORM_GROUP, Arrays.asList(VER_GROUP, URL_GROUP));

            if (platformBoardUrl.isEmpty()) return null;

            Map.Entry<String, Map<String, String>> result = null;
            for (Iterator<Map.Entry<String, Map<String, String>>> it = platformBoardUrl.entrySet().iterator(); it.hasNext(); ) {

                if (result == null) {
                    result = it.next();

                    String ver = result.getValue().get(VER_GROUP);
                    ver = (ver != null ? ver.trim() : null);

                    String url = result.getValue().get(URL_GROUP);
                    if (url != null && !url.isEmpty()) {
                        try {
                            new URL(url);
                        } catch (MalformedURLException e) {
                            this.logProxy.cliError("Invalid URL in comment %s %s::%s::%s - %s%n", BOARDMANAGER_MARKER,
                                    result.getKey(), ver, url, e.getMessage());
                            result = null;
                        }
                    }
                } else {
                    Map.Entry<String, Map<String, String>> skipped = it.next();
                    this.logProxy.cliError("Ignoring additional comment %s %s::%s::%s%n", BOARDMANAGER_MARKER, skipped.getKey(),
                            skipped.getValue().get(VER_GROUP), skipped.getValue().get(URL_GROUP));
                }
            }

            if (result != null) {
                return new BoardManager(result.getKey(), result.getValue().get(VER_GROUP), result.getValue().get(URL_GROUP));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }

    public Map<String, String> dependentLibsFromMainSketchSource(String source) {
        try {
            return extractMarkersKeyValue(
                    extractAllCommentsFromSource(source), DEPENDS_ON_LIB_VERSION, DEPENDSON_MARKER, LIB_GROUP, VER_GROUP);
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
        return Collections.emptyMap();
    }

    private Map<String, Map<String, String>> extractMarkersKeyAndParams(
            List<String> comments, Pattern marker, String markerName, String keyGroup, List<String> paramGroups) {

        if (comments == null || comments.isEmpty()) return Collections.emptyMap();

        return comments.stream()
                .flatMap(data -> Stream.of(NEW_LINE_SPLITTER.split(data)))
                .collect(
                        LinkedHashMap::new,
                        (result, comment) -> {
                            Matcher matcher = marker.matcher(comment);
                            if (matcher.matches()) {
                                String key = matcher.group(keyGroup);
                                Map<String, String> params = new LinkedHashMap<>();
                                if (paramGroups != null) {
                                    paramGroups.forEach(pg -> params.put(pg, matcher.group(pg)));
                                }
                                result.put(key, params);
                            } else if (comment.matches("^[^@]*" + markerName + "(?:\\s|$)")) {
                                this.logProxy.uiInfo("Ignoring duplicated or invalid marker comment:\n" + comment);
                            }
                        },
                        LinkedHashMap::putAll
                );
    }

    private Map<String, String> extractMarkersKeyValue(
            List<String> comments, Pattern marker, String markerName, String keyGroup, String valueGroup) {

        return extractMarkersKeyAndParams(comments, marker, markerName, keyGroup, Collections.singletonList(valueGroup)).entrySet().stream()
                .map(entry -> {
                    String v = entry.getValue().get(valueGroup);
                    if (v != null) v = v.trim();
                    return new AbstractMap.SimpleEntry<>(entry.getKey(), v);
                })
                .collect(HashMap::new, (result, entry) -> result.put(entry.getKey(), entry.getValue()), HashMap::putAll);
    }

    private CharStream buildFromStream(InputStream stream) throws IOException {
        // return CharStreams.fromStream(stream); // in newer antlr versions
        return new ANTLRInputStream(stream);
    }

    private List<String> extractAllCommentsFromSource(String source) throws IOException {
        if (source == null || source.isEmpty()) return Collections.emptyList();

        List<String> result = new ArrayList<>();

        try (ByteArrayInputStream sourceCodeStream = new ByteArrayInputStream(source.getBytes())) {

            CommonTokenStream commonTokenStream = new CommonTokenStream(new CPP14Lexer(buildFromStream(sourceCodeStream)));
            commonTokenStream.fill();

            for (int index = 0; index < commonTokenStream.size(); ++index) {

                Token token = commonTokenStream.get(index);

                if (token.getType() != CPP14Parser.Whitespace && token.getChannel() == CPP14Lexer.COMMENTS) {

                    List<Token> hiddenTokensToLeft = commonTokenStream.getHiddenTokensToLeft(index);
                    for (int i = 0; hiddenTokensToLeft != null && i < hiddenTokensToLeft.size(); i++)
                    {
                        if (hiddenTokensToLeft.get(i).getType() != CPP14Parser.Whitespace)
                        {
                            String comment = hiddenTokensToLeft.get(i).getText();
                            result.add(comment);
                        }
                    }

                }
            }
        }

        return result;
    }
}
