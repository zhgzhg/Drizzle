package com.github.zhgzhg.drizzle.utils.source;

import com.github.gundy.semver4j.model.Version;
import com.github.zhgzhg.drizzle.parser.CPP14Lexer;
import com.github.zhgzhg.drizzle.parser.CPP14Parser;
import com.github.zhgzhg.drizzle.utils.json.ProjectSettings;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import processing.app.Editor;
import processing.app.SketchFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class SourceExtractor {
    public static final String ERR_DETERMINING_PRIMARY_SKETCH_FILE = "Failed determining the primary sketch file!";

    public static final String DEPENDSON_MARKER = "@DependsOn";
    public static final String BOARDMANAGER_MARKER = "@BoardManager";
    public static final String BOARDNAME_MARKER = "@Board";
    public static final String BOARDSETTINGS_MARKER = "@BoardSettings";
    public static final String PREFERENCES_MARKER = "@Preferences";
    public static final String ARDUINOTOOL_MARKER = "@ArduinoTool";


    public static final String PACKAGE_PROVIDER_GROUP = "package";
    public static final String PLATFORM_GROUP = "platform";
    public static final String LIB_GROUP = "lib";
    public static final String TOOL_GROUP = "tool";
    public static final String VER_GROUP = "ver";
    public static final String URL_GROUP = "url";
    public static final String BOARD_GROUP = "board";
    public static final String MENU_GROUP = "boardmenu";
    public static final String PREFDEF_GROUP = "prefdef";


    public static final Pattern NEW_LINE_SPLITTER = Pattern.compile("\\R+");

    public static final Pattern BOARD_MANAGER = Pattern.compile("^[^@]*"
            + BOARDMANAGER_MARKER + "\\s+(?<" + PLATFORM_GROUP + ">[^:]+)::(?<" + VER_GROUP + ">[^:]+)(::(?<" + URL_GROUP + ">.*))?$");

    public static final Pattern BOARD = Pattern.compile("^[^@]*" + BOARDNAME_MARKER + "\\s+((?<" + PACKAGE_PROVIDER_GROUP
            + ">[^:]+)::)?" + "(?<" + PLATFORM_GROUP + ">[^:]+)::(?<" + BOARD_GROUP + ">.+)$");

    public static final Pattern BOARD_SETTINGS = Pattern.compile("^[^@]*"
            + BOARDSETTINGS_MARKER + "\\s+(?<" + PLATFORM_GROUP + ">[^:]+)::(?<" + BOARD_GROUP + ">[^:]+)::(?<" + MENU_GROUP + ">.+)$");

    public static final Pattern PREFERENCES = Pattern.compile("^[^@]*" + PREFERENCES_MARKER + "\\s+((?<" + PACKAGE_PROVIDER_GROUP
            + ">[^:]+)::)?" + "(?<" + PLATFORM_GROUP + ">[^:]+)::(?<" + BOARD_GROUP + ">[^:]+)::(?<" + PREFDEF_GROUP + ">.+)$");

    public static final Pattern DEPENDS_ON_LIB_VERSION =
            Pattern.compile("^[^@]*" + DEPENDSON_MARKER + "\\s+(?<" + LIB_GROUP + ">[^:]+)::(?<" + VER_GROUP + ">.+)$");

    public static final Pattern ARDUINO_TOOL_VERSION = Pattern.compile("^[^@]*"
            + ARDUINOTOOL_MARKER + "\\s+(?<" + TOOL_GROUP + ">[^:]+)::(?<"+ VER_GROUP + ">[^:]+)::(?<" + URL_GROUP + ">.+)$");

    private Editor editor;
    private File drizzleJsonFile;

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
            return "Board{providerPackage='" + providerPackage + "', platform='" + platform + '\'' + ", name='" + name + '\'' + '}';
        }
    }

    public static class BoardSettings {
        public final Board board;
        public final List<List<String>> clickableOptions;

        public BoardSettings(Board board) {
            Objects.requireNonNull(board);
            this.board = new Board(
                    board.providerPackage,
                    (TextUtils.isNullOrBlank(board.platform) || "*".equals(board.platform) ? null : board.platform),
                    (TextUtils.isNullOrBlank(board.name) || "*".equals(board.name) ? null : board.name)
            );
            this.clickableOptions = new LinkedList<>();
        }

        public boolean suitsRequirements(String platformName, String boardName) {
            boolean matching = (TextUtils.isNullOrBlank(board.platform) && TextUtils.isNullOrBlank(board.name));

            if (!matching) {
                if (TextUtils.allNotBlank(board.platform, board.name)) {
                    matching = board.platform.equals(platformName) && board.name.equals(boardName);
                } else if (TextUtils.isNotNullOrBlank(board.platform)) {
                    matching = board.platform.equals(platformName);
                } else if (TextUtils.isNotNullOrBlank(board.name)) {
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

    public static class Preferences {
        public final Board board;
        public final Map<String, String> preferences;

        public Preferences(Board board) {
            Objects.requireNonNull(board);
            this.board = new Board(
                    (TextUtils.isNullOrBlank(board.providerPackage) || "*".equals(board.providerPackage) ? null : board.providerPackage),
                    (TextUtils.isNullOrBlank(board.platform) || "*".equals(board.platform) ? null : board.platform),
                    (TextUtils.isNullOrBlank(board.name) || "*".equals(board.name) ? null : board.name)
            );
            this.preferences = new LinkedHashMap<>();
        }

        public boolean suitsRequirements(String providerPackage, String platformName, String boardName) {

            boolean matching = TextUtils.allNullOrBlank(providerPackage, platformName, boardName);

            if (!matching) {
                if (TextUtils.allNotBlank(board.providerPackage, board.platform, board.name)) {
                    matching = board.providerPackage.equals(providerPackage) && board.platform.equals(platformName)
                            && board.name.equals(boardName);
                } else if (TextUtils.allNotBlank(board.platform, board.name)) {
                    matching = board.platform.equals(platformName) && board.name.equals(boardName);
                } else if (TextUtils.isNotNullOrBlank(board.platform)) {
                    matching = board.platform.equals(platformName);
                } else if (TextUtils.isNotNullOrBlank(board.name)) {
                    matching = board.name.equals(boardName);
                }
            }

            return matching;
        }

        @Override
        public String toString() {
            return "Preferences{" + "board=" + board + ", preferences=" + preferences + '}';
        }
    }

    public static class DependentLibrary {
        public final String name;
        public final String version;
        public final String arduinoCliFmt;

        public DependentLibrary(String name, String version) {
            this.name = name;
            this.version = version;

            URL url = TextUtils.toURL(version, null);
            if (url == null) {
                if (TextUtils.isNullOrBlank(version) || "*".equals(TextUtils.trim(version," ()"))) {
                    this.arduinoCliFmt = name;
                } else {
                    Version parsedVer = Version.fromString(version);
                    this.arduinoCliFmt = name + "@" + parsedVer.getMajor() + "." + parsedVer.getMinor() + "." + parsedVer.getPatch()
                            + (parsedVer.getBuildIdentifiers().isEmpty() ? "" : parsedVer.getBuildIdentifiers().stream()
                            .map(Version.Identifier::toString).collect(Collectors.joining(".", "+", "")));
                }
            } else if (url.toString().toLowerCase().endsWith(".zip")) {
                this.arduinoCliFmt = "--zip-path " + url.toString();
            } else if (url.getPath().toLowerCase().endsWith(".git")){
                this.arduinoCliFmt = "--git-url " + url.toString();
            } else {
                this.arduinoCliFmt = name;
            }
        }

        @Override
        public String toString() {
            return "DependentLibrary{name='" + name + "', version='" + version
                    + "', arduinoCliFmt='" + arduinoCliFmt + "'}";
        }
    }

    public static class ArduinoTool {
        public final String name;
        public final String version;
        public final String url;

        public ArduinoTool(final String name, final String version, final String url) {
            this.name = name;
            this.version = version;
            this.url = url;
        }

        @Override
        public String toString() {
            return "ArduinoTool{" + "name='" + name + '\'' + ", version='" + version + '\'' + ", url='" + url + '\'' + '}';
        }
    }

    public SourceExtractor(Editor editor, LogProxy logProxy) {
        this.editor = editor;
        this.logProxy = logProxy;
        this.locateDrizzleJsonFile();
    }

    private boolean locateDrizzleJsonFile() {
        if (this.drizzleJsonFile == null && editor != null && editor.getSketch() != null) {
            SketchFile primaryFile = editor.getSketch().getPrimaryFile();
            if (primaryFile != null) {
                Path containingDir = primaryFile.getFile().toPath().getParent();
                this.drizzleJsonFile = new File(containingDir.toFile(), "drizzle.json");
            }
        }

        return this.drizzleJsonFile != null && this.drizzleJsonFile.exists();
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

    public ProjectSettings loadAllFromDrizzleJson() {
        if (!locateDrizzleJsonFile()) return null;

        try {
            String json = new String(Files.readAllBytes(this.drizzleJsonFile.toPath()));
            return ProjectSettings.fromJSON(json, this.logProxy);
        } catch (IOException ex) {
            logProxy.cliErrorln(ex);
        }

        return null;
    }

    public Board dependentBoardFromMainSketchSource(String source) {
        ProjectSettings projectSettings = this.loadAllFromDrizzleJson();
        if (projectSettings != null) {
            return projectSettings.getBoard();
        }

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


                if (result == null && TextUtils.allNotBlank(platformName, boardName)) {
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
        ProjectSettings projectSettings = this.loadAllFromDrizzleJson();
        if (projectSettings != null) {
            List<BoardSettings> boardSettings = projectSettings.getBoardSettings();
            return (boardSettings != null ? boardSettings : Collections.emptyList());
        }

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

                List<String> menusToClick = Arrays.stream(menus.split("\\|\\|")).collect(Collectors.toCollection(ArrayList::new));
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

    public List<Preferences> dependentPreferencesFromMainSketchSource(String source) {
        ProjectSettings projectSettings = this.loadAllFromDrizzleJson();
        if (projectSettings != null) {
            List<Preferences> preferences = projectSettings.getPreferences();
            return (preferences != null ? preferences : Collections.emptyList());
        }

        try {
            Map<String, Map<String, String>> boardAndPreferences = extractMarkersKeyAndParams(
                    extractAllCommentsFromSource(source), PREFERENCES, PREFERENCES_MARKER, PACKAGE_PROVIDER_GROUP,
                    Arrays.asList(PLATFORM_GROUP, BOARD_GROUP, PREFDEF_GROUP)
            );

            List<Preferences> result = new ArrayList<>(boardAndPreferences.size());
            boardAndPreferences.forEach((providerPackageName, boardStuff) -> {
                String platformName = boardStuff.get(PLATFORM_GROUP);
                String boardName = boardStuff.get(BOARD_GROUP);

                Preferences preferences = new Preferences(new Board(providerPackageName, platformName, boardName));

                String preferenceDefs = boardStuff.get(PREFDEF_GROUP);
                if (preferenceDefs == null && preferenceDefs.isEmpty()) return;

                List<String> prefDefPairs = Arrays.stream(preferenceDefs.split("\\|\\|")).collect(Collectors.toCollection(ArrayList::new));
                int lastItemIdx = prefDefPairs.size() - 1;
                if (prefDefPairs.get(lastItemIdx) == null || prefDefPairs.get(lastItemIdx).isEmpty()) {
                    prefDefPairs.remove(lastItemIdx);
                }
                if (prefDefPairs.isEmpty()) return;

                for (String mc : prefDefPairs) {
                    List<String> keyAndVal = Arrays.asList(mc.split("=", 2));
                    if (keyAndVal.size() < 2) {
                        logProxy.cliError("Cannot extract preference key=value from %s%n", mc);
                        continue;
                    }
                    preferences.preferences.put(keyAndVal.get(0), keyAndVal.get(1));
                }

                result.add(preferences);
            });

            return result;
        } catch (IOException e) {
            this.logProxy.cliErrorln(e);
        }

        return Collections.emptyList();
    }

    public BoardManager dependentBoardManagerFromMainSketchSource(String source) {
        ProjectSettings projectSettings = this.loadAllFromDrizzleJson();
        if (projectSettings != null) {
            return projectSettings.getBoardManager();
        }

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
                    if (TextUtils.isNotNullOrBlank(url)) {
                        final Map.Entry<String, Map<String, String>> res = result;
                        final String version = ver;
                        if (TextUtils.toURL(url,
                                ex -> logProxy.cliError("Invalid URL in comment %s %s::%s::%s - %s%n", BOARDMANAGER_MARKER,
                                        res.getKey(), version, url, ex.getMessage())) == null) {

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

    public Map<String, DependentLibrary> dependentLibsFromMainSketchSource(String source) {
        ProjectSettings projectSettings = this.loadAllFromDrizzleJson();
        if (projectSettings != null) {
            Map<String, DependentLibrary> libraries = projectSettings.getLibraries();
            return (libraries != null ? libraries : Collections.emptyMap());
        }

        try {
            return extractMarkersKeyValue(extractAllCommentsFromSource(source), DEPENDS_ON_LIB_VERSION,
                    DEPENDSON_MARKER, LIB_GROUP, VER_GROUP)
                    .entrySet().stream()
                    .map(entry -> new DependentLibrary(entry.getKey(), entry.getValue()))
                    .collect(Collectors.toMap(de -> de.name, Function.identity(), (de1, de2) -> de1, LinkedHashMap::new));
        } catch (IOException e) {
            e.printStackTrace(this.logProxy.stderr());
        }
        return Collections.emptyMap();
    }

    public List<ArduinoTool> arduinoToolsFromMainSketchSource(String source) {
        ProjectSettings projectSettings = this.loadAllFromDrizzleJson();
        if (projectSettings != null) {
            return new ArrayList<>(projectSettings.getArduinoIdeTools().values());
        }

        try {
            Map<String, Map<String, String>> toolVerUrl = extractMarkersKeyAndParams(extractAllCommentsFromSource(source),
                    ARDUINO_TOOL_VERSION, ARDUINOTOOL_MARKER, TOOL_GROUP, Arrays.asList(VER_GROUP, URL_GROUP));

            return toolVerUrl.entrySet().stream()
                    .map(entry -> new ArduinoTool(entry.getKey(), entry.getValue().get(VER_GROUP),
                            TextUtils.trim(entry.getValue().get(URL_GROUP), " "))
                    )
                    .filter(at -> null != at
                            && TextUtils.isNotNullOrBlank(at.url)
                            && null != TextUtils.toURL(at.url, ex -> logProxy.cliError("Invalid URL in comment %s %s::%s::%s - %s%n",
                                ARDUINOTOOL_MARKER, at.name, at.version, at.url, ex.getMessage()))
                    )
                    .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace(this.logProxy.stderr());
        }

        return Collections.emptyList();
    }

    private Map<String, Map<String, String>> extractMarkersKeyAndParams(List<String> comments, Pattern marker, String markerName,
            String keyGroup, List<String> paramGroups) {
        return this.extractMarkersKeyAndParams(comments, marker, markerName, keyGroup, paramGroups, data -> null);
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
