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
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class SourceExtractor {
    public static final String ERR_DETERMINING_PRIMARY_SKETCH_FILE = "Failed determining the primary sketch file!";

    public static final String DEPENDSON_MARKER = "@DependsOn";
    public static final String BOARDMANAGER_MARKER = "@BoardManager";
    public static final String BOARDNAME_MARKER = "@Board";
    public static final String BOARDSETTINGS_MARKER = "@BoardSettings";


    public static final String PACKAGE_PROVIDER_GROUP = "package";
    public static final String PLATFORM_GROUP = "platform";
    public static final String LIB_GROUP = "lib";
    public static final String VER_GROUP = "ver";
    public static final String URL_GROUP = "url";
    public static final String BOARD_GROUP = "board";
    public static final String MENU_GROUP = "boardmenu";


    public static final Pattern NEW_LINE_SPLITTER = Pattern.compile("\\R+");

    public static final Pattern BOARD_MANAGER = Pattern.compile("^[^@]*"
            + BOARDMANAGER_MARKER + "\\s+(?<" + PLATFORM_GROUP + ">[^:]+)::(?<" + VER_GROUP + ">[^:]+)(::(?<" + URL_GROUP + ">.*))?$");

    public static final Pattern BOARD = Pattern.compile("^[^@]*" + BOARDNAME_MARKER + "\\s+((?<" + PACKAGE_PROVIDER_GROUP + ">[^:]+)::)?"
                    + "(?<" + PLATFORM_GROUP + ">[^:]+)::(?<" + BOARD_GROUP + ">.+)$");

    public static final Pattern BOARD_SETTINGS = Pattern.compile("^[^@]*"
            + BOARDSETTINGS_MARKER + "\\s+(?<" + PLATFORM_GROUP + ">[^:]+)::(?<" + BOARD_GROUP + ">[^:]+)::(?<" + MENU_GROUP + ">.+)$");

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
        public final String providerPackage;
        public final String platform;
        public final String name;

        public Board(final String providerPackage, final String platform, final String name) {
            this.providerPackage = providerPackage;
            this.platform = platform;
            this.name = name;
        }

        @Override
        public String toString() {
            return "Board{providerPackage=\'" + providerPackage + "\', platform='" + platform + '\'' + ", name='" + name + '\'' + '}';
        }
    }

    public static class BoardSettings {
        public final Board board;
        public final List<List<String>> clickableOptions;

        public BoardSettings(final Board board) {
            Objects.requireNonNull(board);
            this.board = new Board(
                    board.providerPackage,
                    (board.platform == null || board.platform.isEmpty() || "*".equals(board.platform) ? null : board.platform),
                    (board.name == null || board.name.isEmpty() || "*".equals(board.name) ? null : board.name)
            );
            this.clickableOptions = new LinkedList<>();
        }

        public boolean suitsRequirements(String platformName, String boardName) {
            boolean matching = ((board.platform == null || board.platform.isEmpty()) && (board.name == null || board.name.isEmpty()));

            if (!matching) {
                if (board.platform != null && !board.platform.isEmpty() && board.name != null && !board.name.isEmpty()) {
                    matching = board.platform.equals(platformName) && board.name.equals(boardName);
                } else if (board.platform != null && !board.platform.isEmpty()) {
                    matching = board.platform.equals(platformName);
                } else if (board.name != null && !board.name.isEmpty()) {
                    matching = board.name.equals(boardName);
                }
            }

            return matching;
        }

        @Override
        public String toString() {
            return "BoardSettings{" + "board=" + board.toString().replaceFirst("providerPackage='[^']*', ", "")
                    + ", clickableOptions=" + clickableOptions + '}';
        }
    }

    public SourceExtractor(LogProxy logProxy) {
        this.logProxy = logProxy;
    }

    public static String loadSourceFromPrimarySketch(Editor editor) throws IOException {
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
            Map<String, Map<String, String>> boardPlatformAndNames = extractMarkersKeyAndParams(extractAllCommentsFromSource(source), BOARD,
                    BOARDNAME_MARKER, PACKAGE_PROVIDER_GROUP, Arrays.asList(PLATFORM_GROUP, BOARD_GROUP),
                    parsedParams -> null
            );

            Board result = null;

            for (Iterator<Map.Entry<String, Map<String, String>>> iter = boardPlatformAndNames.entrySet().iterator(); iter.hasNext(); ) {
                Map.Entry<String, Map<String, String>> next = iter.next();
                String providerPackage = next.getKey();
                Map<String, String> boardCandidate = next.getValue();
                String platformName = boardCandidate.get(PLATFORM_GROUP);
                String boardName = boardCandidate.get(BOARD_GROUP);


                if (result == null && platformName != null && !platformName.isEmpty() && boardName != null
                        && !boardName.isEmpty()) {
                    result = new Board(providerPackage, platformName, boardName);
                } else {
                    this.logProxy.cliError("Ignoring additional %s %s::%s::%s%n", BOARDNAME_MARKER, providerPackage,
                            platformName, boardName);
                }
            }

            return result;
        } catch (IOException e) {
            this.logProxy.cliErrorln(e);
        }

        return null;
    }

    public List<BoardSettings> dependentBoardClickableSettingsFromMainSketchSource(String source) {
        try {
            Map<String, Map<String, String>> boardAndClickableSettings = extractMarkersKeyAndParams(
                    extractAllCommentsFromSource(source), BOARD_SETTINGS, BOARDSETTINGS_MARKER, PLATFORM_GROUP,
                    Arrays.asList(BOARD_GROUP, MENU_GROUP)
            );

            List<BoardSettings> result = new ArrayList<>(boardAndClickableSettings.size());
            boardAndClickableSettings.forEach((platformName, boardStuff) -> {
                String boardName = boardStuff.get(BOARD_GROUP);
                Board board = new Board(null, !platformName.equals("*") ? platformName : null, !"*".equals(boardName) ? boardName : null);
                BoardSettings boardSettings = new BoardSettings(board);

                String menus = boardStuff.get(MENU_GROUP);
                if (menus == null && menus.isEmpty()) return;

                List<String> menusToClick = Arrays.asList(menus.split("\\|\\|"));
                int lastItemIdx = menusToClick.size() - 1;
                if (menusToClick.get(lastItemIdx) == null || menusToClick.get(lastItemIdx).isEmpty()) {
                    menusToClick.remove(lastItemIdx);
                }
                if (menusToClick.isEmpty()) return;

                for (String mc : menusToClick) {
                    List<String> path = Arrays.asList(mc.split("->"));
                    boardSettings.clickableOptions.add(path);
                }

                result.add(boardSettings);
            });

            return result;
        } catch (IOException e) {
            this.logProxy.cliErrorln(e);
        }

        return Collections.emptyList();
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
            e.printStackTrace(this.logProxy.stderr());
        }

        return null;
    }

    public Map<String, String> dependentLibsFromMainSketchSource(String source) {
        try {
            return extractMarkersKeyValue(
                    extractAllCommentsFromSource(source), DEPENDS_ON_LIB_VERSION, DEPENDSON_MARKER, LIB_GROUP, VER_GROUP);
        } catch (IOException e) {
            e.printStackTrace(this.logProxy.stderr());
        }
        return Collections.emptyMap();
    }

    private Map<String, Map<String, String>> extractMarkersKeyAndParams(List<String> comments, Pattern marker, String markerName,
            String keyGroup, List<String> paramGroups) {
        return this.extractMarkersKeyAndParams(comments, marker, markerName, keyGroup, paramGroups, (data) -> null);
    }

    private Map<String, Map<String, String>> extractMarkersKeyAndParams(List<String> comments, Pattern marker, String markerName,
            String keyGroup, List<String> paramGroups, Function<Map<String, String>, String> onNullKey) {

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
                                if (key == null) {
                                    key = onNullKey.apply(params);
                                }
                                if (!result.containsKey(key)) {
                                    result.put(key, params);
                                } else {
                                    this.logProxy.uiWarn("Ignoring duplicated marker comment:\n" + comment);
                                }
                            } else if (comment.matches("^[^@]*" + markerName + "(?:\\s|$)")) {
                                this.logProxy.uiWarn("Ignoring invalid marker comment:\n" + comment);
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

                if (token != null && token.getType() != CPP14Parser.Whitespace && token.getChannel() == CPP14Lexer.COMMENTS) {
                    String comment = token.getText();
                    result.add(comment);
                }
            }
        }

        return result;
    }
}
