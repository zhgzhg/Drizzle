package com.github.zhgzhg.drizzle;

import cc.arduino.contributions.GPGDetachedSignatureVerifier;
import cc.arduino.contributions.ProgressListener;
import cc.arduino.contributions.libraries.ContributedLibrary;
import cc.arduino.contributions.libraries.LibraryInstaller;
import cc.arduino.contributions.packages.ContributedPackage;
import cc.arduino.contributions.packages.ContributedPlatform;
import cc.arduino.contributions.packages.ContributionInstaller;
import cc.arduino.view.NotificationPopup;
import com.github.gundy.semver4j.SemVer;
import com.github.gundy.semver4j.model.Version;
import com.github.zhgzhg.drizzle.utils.arduino.ArduinoIDEToolsInstaller;
import com.github.zhgzhg.drizzle.utils.arduino.CompilationInvoker;
import com.github.zhgzhg.drizzle.utils.arduino.ExternLibFileInstaller;
import com.github.zhgzhg.drizzle.utils.arduino.IDECompilationHook;
import com.github.zhgzhg.drizzle.utils.arduino.UILocator;
import com.github.zhgzhg.drizzle.utils.arduino.UpdateUtils;
import com.github.zhgzhg.drizzle.utils.collection.CollectionUtils;
import com.github.zhgzhg.drizzle.utils.file.FileUtils;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.log.ProgressPrinter;
import com.github.zhgzhg.drizzle.utils.misc.MutableBoolean;
import com.github.zhgzhg.drizzle.utils.source.SourceExtractor;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;
import processing.app.Base;
import processing.app.BaseNoGui;
import processing.app.Editor;
import processing.app.EditorConsole;
import processing.app.EditorStatus;
import processing.app.EditorTab;
import processing.app.PreferencesData;
import processing.app.SketchFile;
import processing.app.debug.RunnerException;
import processing.app.debug.TargetBoard;
import processing.app.debug.TargetPackage;
import processing.app.debug.TargetPlatform;
import processing.app.tools.Tool;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Drizzle implements Tool {

    public static final String MENUS_HOLDER_TITLE = "Drizzle";
    public static final String MENU_APPLY_MARKERS_TITLE = "Apply Markers";
    public static final String MENU_AUTOGEN_ALL_MARKERS_TITLE = "Auto-generate @Board* and @Dependency Markers (via compilation)";
    public static final String MENU_AUTOGEN_BOARD_MARKERS_TITLE = "Auto-generate @Board* Markers";
    public static final String MENU_SHOW_AVAILABLE_FBNS_TITLE = "List Available FQBNs";
    public static final String MENU_ARDUINO_TOOL_INSTALL_TITLE = "Install tools marked with @ArduinoTool";
    public static final String MENU_ABOUT_DRIZZLE_TITLE = "About Drizzle";

    private final GPGDetachedSignatureVerifier gpgDetachedSignatureVerifier = new GPGDetachedSignatureVerifier();
    private Editor editor;
    private IDECompilationHook normalCompilationHook;
    private ContributionInstaller contributionInstaller;
    private LibraryInstaller libraryInstaller;
    private SourceExtractor sourceExtractor;

    private ProgressListener progressListener;
    private ProgressPrinter progressPrinter;
    private LogProxy<EditorConsole> logProxy;
    private UILocator uiLocator;

    private JMenuItem applyDrizzleMarkersMenu = new JMenuItem(MENU_APPLY_MARKERS_TITLE);
    private JMenuItem boardSettingsAndDependenciesGeneratorMenu = new JMenuItem(MENU_AUTOGEN_ALL_MARKERS_TITLE);
    private JMenuItem boardAndSettingsGeneratorMenu = new JMenuItem(MENU_AUTOGEN_BOARD_MARKERS_TITLE);
    private JMenuItem availableBoardFqdnListMenu = new JMenuItem(MENU_SHOW_AVAILABLE_FBNS_TITLE);
    private JMenuItem arduinoToolInstallMenu = new JMenuItem(MENU_ARDUINO_TOOL_INSTALL_TITLE);
    private JMenuItem aboutDrizzleMenu = new JMenuItem(MENU_ABOUT_DRIZZLE_TITLE);

    @Override
    public void init(final Editor editor) {
        this.editor = editor;
        this.uiLocator = new UILocator(editor);
        this.logProxy = new LogProxy<EditorConsole>() {
            @Override
            public void uiError(final String format, final Object... params) { editor.statusError(String.format(format != null ? format : "null", params)); }

            @Override
            public void uiError(final Throwable t) { Base.showError("Error", t.getMessage(), t); }

            @Override
            public void uiWarn(final String format, final Object... params) { editor.statusNotice(String.format(format != null ? format : "null", params)); }

            @Override
            public void uiInfo(final String format, final Object... params) { editor.statusNotice(String.format(format != null ? format : "null", params)); }
        };

        this.progressPrinter = new ProgressPrinter(logProxy);
        this.sourceExtractor = new SourceExtractor(editor, logProxy);
        this.progressListener = progress -> progressPrinter.progress();

        this.contributionInstaller = new ContributionInstaller(BaseNoGui.getPlatform(), gpgDetachedSignatureVerifier);
        this.libraryInstaller = new LibraryInstaller(BaseNoGui.getPlatform(), gpgDetachedSignatureVerifier);

        this.normalCompilationHook = new IDECompilationHook(editor, logProxy,
                (_editor, _logProxy, _context) -> {
                    String msg = "Compiling with the help of Drizzle...";
                    logProxy.uiInfo(msg);
                    logProxy.cliInfoln(msg);
                    String source;
                    try {
                        source = SourceExtractor.loadSourceFromPrimarySketch(editor);
                    } catch (IOException ex) {
                        logProxy.cliErrorln(ex);
                        logProxy.uiError(SourceExtractor.PREFERENCES_MARKER + ": error " + ex.getMessage());
                        return false;
                    }

                    TargetPlatform targetPlatform = BaseNoGui.getTargetPlatform();
                    String targetPackageName = null;
                    String targetPlatformName = null;
                    if (targetPlatform != null) {
                        TargetPackage containerPackage = targetPlatform.getContainerPackage();
                        if (containerPackage != null) {
                            targetPackageName = containerPackage.getId();
                        }
                        targetPlatformName = targetPlatform.getId();
                    }

                    TargetBoard targetBoard = BaseNoGui.getTargetBoard();
                    String targetBoardName = null;
                    String targetBoardId = null;
                    if (targetBoard != null) {
                        targetBoardName = targetBoard.getName();
                        targetBoardId = targetBoard.getId();
                    }

                    SourceExtractor se = new SourceExtractor(_editor, _logProxy);
                    for (SourceExtractor.Preferences pref : se.dependentPreferencesFromMainSketchSource(source)) {
                        if (pref.suitsRequirements(targetPackageName, targetPlatformName, targetBoardId)
                                || pref.suitsRequirements(targetPackageName, targetPlatformName, targetBoardName)) {

                            pref.preferences.entrySet().stream()
                                    .forEach(ent -> {
                                        String k = String.format("runtime.build_properties_custom.%s", ent.getKey());
                                        String v = ent.getValue();
                                        PreferencesData.set(k, v);
                                        _context.put(k, v);
                                    });
                        }
                    }

                    return true;
                },
                (_editor, _logProxy, _context) -> {
                    _context.keySet().stream().forEach(PreferencesData::remove);
                    return true;
                });

        this.applyDrizzleMarkersMenu.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                Drizzle.this.run();
            }
        });

        this.boardSettingsAndDependenciesGeneratorMenu.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                if (getPrimarySketchTabIfOpened() != null) {
                    new Thread(() -> {
                        MutableBoolean hadCompilationIssues = new MutableBoolean();
                        List<String> dependencyMarkers = autogenDependencyMarkers(e, hadCompilationIssues);
                        List<String> boardAndSettingsMarkers = autogenBoardAndBoardSettingsMarkers(e);

                        boardAndSettingsMarkers.add("");
                        boardAndSettingsMarkers.addAll(dependencyMarkers);

                        insertCommentBlockWithMarkersAtStartOfTheCurrentTab(boardAndSettingsMarkers);

                        if (!hadCompilationIssues.get()) {
                            logProxy.cliInfoln("Marker generation is done! Don't forget to save your sketch.");
                        } else {
                            String partialErrMsg = "The compilation finished with an error! The marker generation is done, but may be incomplete!";
                            logProxy.uiError(partialErrMsg); // the error will be displayed in the console as well
                        }
                    }).start();
                }
            }
        });
        this.boardAndSettingsGeneratorMenu.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                if (getPrimarySketchTabIfOpened() != null) {
                    insertCommentBlockWithMarkersAtStartOfTheCurrentTab(autogenBoardAndBoardSettingsMarkers(e));
                    logProxy.cliInfoln("Marker generation is done! Don't forget to save your sketch.");
                }
            }
        });
        this.availableBoardFqdnListMenu.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                logProxy.cliInfoln("----------------------------");
                logProxy.cliInfoln("PackageID:PlatformID:BoardID");
                logProxy.cliInfoln("----------------------------\n");

                BaseNoGui.packages.values().stream()
                        .flatMap(targetPackage -> targetPackage.platforms().stream())
                        .flatMap(targetPlatform -> targetPlatform.getBoards().values().stream())
                        .map(targetBoard -> targetBoard.getFQBN())
                        .distinct()
                        .sorted()
                        .forEach(logProxy::cliInfoln);

                logProxy.cliInfoln("\nTo use in Drizzle use double colons (::) as a separator.");
            }
        });
        this.arduinoToolInstallMenu.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                new Thread(() -> {
                    logProxy.uiInfo("                                                                                                                                                                                                               ");
                    String source;
                    try {
                        source = SourceExtractor.loadSourceFromPrimarySketch(editor);
                    } catch (IOException ex) {
                        logProxy.cliErrorln(ex);
                        logProxy.uiError(ex.getMessage());
                        return;
                    }

                    SourceExtractor se = new SourceExtractor(editor, logProxy);
                    List<SourceExtractor.ArduinoTool> arduinoTools = se.arduinoToolsFromMainSketchSource(source);

                    ArduinoIDEToolsInstaller toolsInstaller = new ArduinoIDEToolsInstaller(logProxy);

                    int installedToolsCount = 0;
                    for (SourceExtractor.ArduinoTool at : arduinoTools) {
                        String installedVer = toolsInstaller.extractInstalledToolVersion(at.name);
                        if (installedVer == null || (!installedVer.isEmpty() && SemVer.satisfies(installedVer, at.version))) {
                            logProxy.cliInfo("Installing %s from %s...%n", at.name, at.url);
                            if (toolsInstaller.installTool(at.name, at.url)) {
                                ++installedToolsCount;
                                logProxy.cliInfo("Successfully installed %s!%n", at.name);
                            } else {
                                logProxy.cliError("Failed installing %s!%n", at.name);
                            }
                        } else {
                            logProxy.cliInfo("Skipped the installation of %s.%n", at.name);
                        }
                    }

                    if (installedToolsCount > 0) {
                        logProxy.uiInfo("You'll have to restart Arduino IDE in for the new tools to get loaded.");
                        logProxy.cliInfoln("Please restart the IDE in order to load the newly installed tools.");
                    }

                    logProxy.cliInfoln("Finished installing Arduino IDE tools.");
                }).start();
            }
        });

        this.aboutDrizzleMenu.addActionListener(e -> {
            JOptionPane.showMessageDialog(null, String.format("Drizzle %s - a dependency helper tool for Arduino IDE%n%n%s%n",
                    DrizzleCLI.version(), UpdateUtils.webUrl()), MENU_ABOUT_DRIZZLE_TITLE, JOptionPane.PLAIN_MESSAGE, null);
        });

        this.editor.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(final ComponentEvent e) {

                Optional<JMenuItem> drizzleMenu = uiLocator.drizzleMenu();
                drizzleMenu
                        .<JPopupMenu>map(dm -> {
                            Container parent = dm.getParent();
                            if (parent instanceof JPopupMenu) {
                                return (JPopupMenu) parent;
                            }
                            return null;
                        })
                        .ifPresent(m -> {
                            int index = m.getComponentIndex(drizzleMenu.get());

                            JMenu drizzleMenus = new JMenu(MENUS_HOLDER_TITLE);
                            drizzleMenus.add(applyDrizzleMarkersMenu);
                            drizzleMenus.add(boardAndSettingsGeneratorMenu);
                            drizzleMenus.add(boardSettingsAndDependenciesGeneratorMenu);
                            drizzleMenus.add(availableBoardFqdnListMenu);
                            drizzleMenus.add(arduinoToolInstallMenu);
                            drizzleMenus.add(aboutDrizzleMenu);

                            m.add(drizzleMenus, index);
                            m.remove(index + 1);
                            m.revalidate();
                            editor.removeComponentListener(this);
                        });

                SwingUtilities.invokeLater(() -> {
                    if (UpdateUtils.arduinoRevision() < 10814) {
                        JOptionPane.showMessageDialog(editor, String.format(
                                "Drizzle %s is not compatible with Arduino IDE %s!!!%nPlease downgrade the plugin or update your IDE.%n",
                                DrizzleCLI.version(), UpdateUtils.arduinoVersion()), "Incompatible version of Drizzle and Arduino IDE",
                                JOptionPane.WARNING_MESSAGE, null);
                        return;
                    }

                    normalCompilationHook.hookOnSketchController();

                    if (!UpdateUtils.isTheLatestVersion(logProxy)) {
                        final NotificationPopup[] notificationHolder = new NotificationPopup[1];
                        notificationHolder[0] = UpdateUtils.createNewVersionPopupNotification(editor, logProxy,
                                "Install the update & close the IDE", () -> {

                                notificationHolder[0].close();
                                ArduinoIDEToolsInstaller drizzleInstaller = new ArduinoIDEToolsInstaller(logProxy);
                                logProxy.cliInfo("Downloading & installing the latest version of Drizzle (please wait)... ");

                                boolean isSuccess = drizzleInstaller.installTool(MENUS_HOLDER_TITLE, UpdateUtils.latestVersionOfDistZIP());
                                if (isSuccess) {
                                    drizzleInstaller.selfDestroyJarOnExit();
                                    logProxy.cliWarn("succeeded!%nThe Arduino IDE will be closed in 10 seconds...%n");
                                    drizzleInstaller.killJVM(10);
                                } else {
                                    logProxy.cliError("failed! Please try again later.%n");
                                }
                            }
                        );
                        notificationHolder[0].begin();
                    }
                });
            }
        });
    }

    @Override
    public String getMenuTitle() {
        return MENUS_HOLDER_TITLE;
    }

    @Override
    public void run() {
        this.uiLocator.editorConsole().ifPresent(editorConsole -> {
            editorConsole.clear();
            this.logProxy.setEditorConsole(new LogProxy.EditorConsoleSupplierAndSetter<EditorConsole>() {
                @Override
                public void run() {
                    EditorConsole.setCurrentEditorConsole(editorConsole);
                }

                @Override
                public EditorConsole get() {
                    return editorConsole;
                }
            });
        });

        this.logProxy.uiInfo("                                                                                                                                                                                                               ");

        new Thread(() -> {
            Optional<JMenuItem> drizzleMenu = uiLocator.drizzleMenu();
            drizzleMenu.ifPresent(dm -> dm.setEnabled(false));

            int installedBoardsCount = installBoards();
            if (installedBoardsCount == 0) {
                this.logProxy.cliErrorln("No platform definitions managed by " + SourceExtractor.BOARDMANAGER_MARKER
                        + " marker in the main sketch were found");
            }

            int installedLibsCount = installLibraries();
            if (installedLibsCount < 0) {
                String err = "Couldn't install some/any libraries - managed by marker " + SourceExtractor.DEPENDSON_MARKER
                        + " in the main sketch!";
                this.logProxy.cliErrorln(err);
                this.logProxy.uiWarn(err);
            }

            if (selectBoard() == 0) {
                this.logProxy.cliInfoln("No default board specified with " + SourceExtractor.BOARDNAME_MARKER
                        + " marker in the main sketch file was found");
            }

            if (selectBoardOptions() == 0) {
                this.logProxy.cliInfoln("No clickable board options specified with " + SourceExtractor.BOARDSETTINGS_MARKER
                        + " marker were matched");
            }

            drizzleMenu.ifPresent(dm -> dm.setEnabled(true));
            this.logProxy.cliInfoln("Done!");
        }).start();
    }

    private int selectBoard() {
        String source;
        try {
            source = SourceExtractor.loadSourceFromPrimarySketch(editor);
        } catch (IOException e) {
            this.logProxy.cliErrorln(e);
            this.logProxy.uiError(e.getMessage());
            return -1;
        }

        SourceExtractor.Board board = sourceExtractor.dependentBoardFromMainSketchSource(source);
        if (board == null) return 0;

        TargetBoard targetBoard = BaseNoGui.indexer.getIndex().getInstalledPlatforms().stream()
                .filter(contributedPlatform ->
                        (board.providerPackage == null || board.providerPackage.equals(contributedPlatform.getParentPackage().getName()))
                        && board.platform.equals(contributedPlatform.getName())
                        && contributedPlatform.getBoards().stream().anyMatch(contribBoard -> board.name.equals(contribBoard.getName()))
                )
                .map(contribPlatf -> BaseNoGui.getTargetPlatform(contribPlatf.getParentPackage().getName(), contribPlatf.getArchitecture()))
                .flatMap(targetPlatform -> targetPlatform.getBoards().values().stream())
                .filter(targtBrd -> board.name.equals(targtBrd.getId()) || board.name.equals(targtBrd.getName()))
                .findFirst()
                .orElse(null);

        if (targetBoard == null) {
            // search in the local hardware folder for suitable projects

            targetBoard = BaseNoGui.packages.values().stream()
                    .filter(p ->
                            board.providerPackage == null || board.providerPackage.equals(p.getId()))
                    .map(targPkg ->
                            targPkg.getPlatforms().get(board.platform))
                    .filter(Objects::nonNull)
                    .flatMap(targPlatf ->
                            targPlatf.getBoards().values().stream())
                    .filter(targtBrd ->
                            board.name.equals(targtBrd.getId()) || board.name.equals(targtBrd.getName()))
                    .findFirst()
                    .orElse(null);
        }

        if (targetBoard == null) {
            this.logProxy.cliErrorln("Failed to pick board based on " + board);
            return -1;
        }

        TargetPlatform targetPlatform = targetBoard.getContainerPlatform();
        TargetPackage targetPackage = targetPlatform.getContainerPackage();
        if (PreferencesData.get("target_package", "").equals(targetPackage.getId())
                && PreferencesData.get("target_platform", "").equals(targetPlatform.getId())
                && PreferencesData.get("board", "").equals(targetBoard.getId())) {
            this.logProxy.cliInfo("Board %s is already selected%n", targetBoard.getName());
            return -1;
        }

        try {
            this.uiLocator.customMenusForCurrentlySelectedBoard()
                    .forEach(b -> {
                        Container parent = b.getParent();
                        if (parent != null) {
                            parent.remove(b);
                        }
                    });
            BaseNoGui.selectBoard(targetBoard);
            BaseNoGui.onBoardOrPortChange();
            Base.INSTANCE.rebuildBoardsMenu();
            this.logProxy.cliInfo("Selected board %s%n", targetBoard.getName());
        } catch (Exception e) {
            e.printStackTrace(this.logProxy.stderr());
        }

        return 1;
    }

    private int installBoards() {
        String source;
        try {
            source = SourceExtractor.loadSourceFromPrimarySketch(editor);
        } catch (IOException e) {
            this.logProxy.cliErrorln(e);
            this.logProxy.uiError(e.getMessage());
            return -1;
        }

        SourceExtractor.BoardManager bmSettings = this.sourceExtractor.dependentBoardManagerFromMainSketchSource(source);
        if (bmSettings == null) return -1;

        String boardUrlsCsv = PreferencesData.get(cc.arduino.Constants.PREF_BOARDS_MANAGER_ADDITIONAL_URLS, "");
        if (bmSettings.url != null && !boardUrlsCsv.toLowerCase().contains(bmSettings.url)) {
            if (!boardUrlsCsv.trim().isEmpty()) {
                boardUrlsCsv += ",";
            }
            boardUrlsCsv += bmSettings.url;
            PreferencesData.set(cc.arduino.Constants.PREF_BOARDS_MANAGER_ADDITIONAL_URLS, boardUrlsCsv);
        }

        this.logProxy.cliInfo("Updating platform definitions list...");
        this.progressPrinter.begin(1, -1, 100, "...");
        this.contributionInstaller.updateIndex(this.progressListener);

        try {
            BaseNoGui.indexer.parseIndex();
        } catch (Exception e) {
            e.printStackTrace(this.logProxy.stderr());
            return 0;
        }

        this.logProxy.cliInfoln(" done!\n");

        this.logProxy.cliInfoln("Preparing platform installation...");
        List<ContributedPlatform> possiblePlatforms = BaseNoGui.indexer.getPackages().stream()
                .map(ContributedPackage::getPlatforms)
                .flatMap(List::stream)
                .filter(p -> bmSettings.platform.equals(p.getName()))
                .collect(Collectors.toList());

        List<String> candidateVersions = possiblePlatforms.stream().map(ContributedPlatform::getParsedVersion).collect(Collectors.toList());

        this.logProxy.cliInfo("%s - required: %s, candidates: %s%n", bmSettings.platform, bmSettings.version,
                TextUtils.reversedCollectionToString(candidateVersions));

        String chosenVersion = SemVer.maxSatisfying(candidateVersions, bmSettings.version);
        if (TextUtils.isNotNullOrBlank(chosenVersion)) {
            int platfIndex = candidateVersions.indexOf(chosenVersion);
            ContributedPlatform platformToInstall = possiblePlatforms.get(platfIndex);
            this.logProxy.cliInfo("Selected platform version %s%n", platformToInstall.getParsedVersion());

            boolean refreshUI = removeOldPlatform(possiblePlatforms, platfIndex);
            refreshUI |= installPlatform(platformToInstall);

            if (refreshUI) rebuildBoardMenuUI();
        } else {
            this.logProxy.cliError("Failed to pick version for platform %s, expression %s%n", bmSettings.platform, bmSettings.version);
        }

        return 1;
    }

    private boolean removeOldPlatform(final List<ContributedPlatform> possiblePlatforms, final int platfIndex) {
        boolean refreshUI = false;

        for (int i = 0; i < possiblePlatforms.size(); ++i) {
            if (i == platfIndex) continue;
            ContributedPlatform previouslyInstalled = possiblePlatforms.get(i);
            if (!previouslyInstalled.isBuiltIn() && previouslyInstalled.isInstalled()) {
                this.contributionInstaller.remove(previouslyInstalled);
                refreshUI = true;
            }
        }
        return refreshUI;
    }

    private boolean installPlatform(final ContributedPlatform platformToInstall) {
        boolean refreshUI = false;

        if (!platformToInstall.isInstalled()) {
            this.logProxy.cliInfo("Installing platform...");
            int printIndex = platformToInstall.isDownloaded() ? 1 : 20;
            this.progressPrinter.begin(1, printIndex, printIndex * 80, ".");
            try {
                this.contributionInstaller.install(platformToInstall, this.progressListener);
                refreshUI = true;
                this.logProxy.cliInfoln(" done!");
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("Can't extract file")
                        && e.getMessage().contains(", file already exists!")) {

                    this.logProxy.cliWarn(" done, but with extraction errors - %s\n"
                            + "\t(You may need to delete the whole platform package if the board is unavailable in the UI.)\n", e.getMessage());
                    this.logProxy.uiWarn("Platform extraction error occured: %s", e.getMessage());
                } else {
                    this.logProxy.cliErrorln();
                    this.logProxy.cliErrorln(e);
                    this.logProxy.uiError(e.getMessage());
                }
            }
        }
        return refreshUI;
    }

    private void rebuildBoardMenuUI() {
        try {
            this.uiLocator.boardsMenu().ifPresent(bm -> {
                Container parent = bm.getParent();
                if (parent != null)
                    parent.remove(bm);
            });
            BaseNoGui.initPackages();
            Base.INSTANCE.rebuildBoardsMenu();
            Base.INSTANCE.rebuildProgrammerMenu();
            Base.INSTANCE.rebuildSketchbookMenus();
        } catch (Exception e) {
            this.logProxy.cliErrorln(e);
        }
    }

    private List<ContributedLibrary> loadAvailableLibraries() {
        try {
            this.logProxy.cliInfo("Updating library info...");
            this.progressPrinter.begin(1, -1, 80, ".");
            libraryInstaller.updateIndex(progressListener);
            this.logProxy.cliInfoln(" done!");
        } catch (Exception e) {
            this.logProxy.cliError("error!%n%s%n", e.getMessage());
            this.logProxy.uiError("Error Updating Packages Info: %s", e.getMessage());
        }

        List<ContributedLibrary> libraries = BaseNoGui.librariesIndexer.getIndex().getLibraries();
        if (libraries == null || libraries.isEmpty()) {
            return Collections.emptyList();
        }

        return libraries;
    }

    private int installLibraries() {
        String source;
        try {
             source = SourceExtractor.loadSourceFromPrimarySketch(editor);
        } catch (IOException e) {
            this.logProxy.cliErrorln(e);
            this.logProxy.uiError(e.getMessage());
            return -1;
        }

        Map<String, SourceExtractor.DependentLibrary> requiredLibs = this.sourceExtractor.dependentLibsFromMainSketchSource(source);
        if (requiredLibs.isEmpty()) {
            return 0;
        }

        List<ContributedLibrary> availableLibraries = loadAvailableLibraries();
        if (availableLibraries.isEmpty()) {
            this.logProxy.cliErrorln("No available libraries were found!");
            return -1;
        }

        List<ContributedLibrary> librariesToInstall = new ArrayList<>();
        int installedLibrariesCount = 0;

        for (SourceExtractor.DependentLibrary entry : requiredLibs.values()) {
            String libName = entry.name;
            String libVer = entry.version;

            List<ContributedLibrary> installCandidates =
                    availableLibraries.stream().filter(lib -> libName.equals(lib.getName())).collect(Collectors.toList());

            if (installCandidates.isEmpty() && libName.contains("_")) {
                String libNameWithSpaces = libName.replace("_", " ");
                installCandidates = availableLibraries.stream()
                        .filter(lib -> libNameWithSpaces.equals(lib.getName())).collect(Collectors.toList());
            }

            List<String> installCandidateVersions = installCandidates.stream()
                    .map(ContributedLibrary::getParsedVersion).collect(Collectors.toList());

            this.logProxy.cliInfo("%s - required: %s, candidates: %s%n", libName, libVer,
                    TextUtils.reversedCollectionToString(installCandidateVersions));

            if (installLibraryFromURI(libName, libVer, requiredLibs.keySet())) {
                ++installedLibrariesCount;
                continue;
            }

            String chosenVersion = SemVer.maxSatisfying(installCandidateVersions, libVer);
            if (TextUtils.isNotNullOrBlank(chosenVersion)) {
                int libIndex = installCandidateVersions.indexOf(chosenVersion);
                ContributedLibrary l = installCandidates.get(libIndex);
                this.logProxy.cliInfo("Picked %s version %s%n", libName, l.getParsedVersion());
                librariesToInstall.add(l);
            } else {
                if (BaseNoGui.librariesIndexer.getInstalledLibraries().getByName(libName) == null) {
                    this.logProxy.cliError("Failed to pick version for %s, expression %s%n", libName, libVer);
                } else {
                    this.logProxy.cliInfo("Picked core library %s%n", libName);
                }
            }
        }

        this.logProxy.cliInfo("Installing libraries...");

        Set<ContributedLibrary> missingTransitiveDependencies = new LinkedHashSet<>();
        Set<ContributedLibrary> missingNotInstalledTransitiveDependencies = new LinkedHashSet<>();
        Map<ContributedLibrary, Set<ContributedLibrary>> transitiveDependencyRequiredBy = new LinkedHashMap<>();

        try {
            this.progressPrinter.begin(1, 5, 80, ".");
            this.libraryInstaller.install(librariesToInstall, this.progressListener);

            librariesToInstall.stream()
                    .map(lib -> new AbstractMap.SimpleEntry<>(lib, BaseNoGui.librariesIndexer.getIndex().resolveDependeciesOf(lib)))
                    .filter(deps -> deps.getValue() != null && !deps.getValue().isEmpty())
                    .forEach(libAndDeps -> libAndDeps.getValue().stream().forEach(dep -> {
                            if (!dep.getInstalledLibrary().isPresent()) {
                                missingNotInstalledTransitiveDependencies.add(dep);
                                transitiveDependencyRequiredBy
                                        .compute(dep, (dependency, origin) -> origin == null ? new LinkedHashSet<>() : origin)
                                        .add(libAndDeps.getKey());
                            }

                            if (!librariesToInstall.stream().anyMatch(lib -> lib.getName().equals(dep.getName()))) {
                                missingTransitiveDependencies.add(dep);
                                transitiveDependencyRequiredBy
                                        .compute(dep, (dependency, origin) -> origin == null ? new LinkedHashSet<>() : origin)
                                        .add(libAndDeps.getKey());
                            }
                        })
                    );

            String currBoardArch = Optional.ofNullable(BaseNoGui.getTargetBoard())
                    .map(TargetBoard::getContainerPlatform)
                    .map(TargetPlatform::getId)
                    .orElse(null);
            if (TextUtils.isNullOrBlank(currBoardArch)) {
                String error = "Cannot determine the target board / platform / architecture!\nThis may indicate a missing or corrupted board package.";
                this.logProxy.cliErrorln(error);
                this.logProxy.uiError(error);
                return -1;
            }

            for (Iterator<Map.Entry<ContributedLibrary, Set<ContributedLibrary>>> it = transitiveDependencyRequiredBy.entrySet().iterator();
                    it.hasNext(); ) {

                Map.Entry<ContributedLibrary, Set<ContributedLibrary>> libAndRequirement = it.next();
                List<String> libArchitectures = libAndRequirement.getKey().getArchitectures();

                if (!libArchitectures.isEmpty() && !libArchitectures.contains("*")
                        && !libArchitectures.contains(currBoardArch)) {

                    missingTransitiveDependencies.remove(libAndRequirement.getKey());
                    missingNotInstalledTransitiveDependencies.remove(libAndRequirement.getKey());
                    it.remove();
                }
            }

            this.uiLocator.sketchIncludeLibraryMenu().ifPresent(im -> Base.INSTANCE.rebuildImportMenu(im));
            this.uiLocator.filesExamplesMenu().ifPresent(em -> Base.INSTANCE.rebuildExamplesMenu(em));
        } catch (Exception e) {
            this.logProxy.cliErrorln(e);
            this.logProxy.uiError(e.getMessage());
            return -2;
        }
        this.logProxy.cliInfoln(" done:");
        librariesToInstall.forEach(l -> logProxy.cliInfo("  %s %s%n", l.getName(), l.getParsedVersion()));

        if (!missingNotInstalledTransitiveDependencies.isEmpty()) {
            this.logProxy.cliError(
                    "Check your %s dependency list! The following transitive dependencies are not installed:%n", MENUS_HOLDER_TITLE);
            missingNotInstalledTransitiveDependencies.forEach(transDep -> this.logProxy.cliError(
                    " - %s, possibly version %s, introduced by: %s%n", transDep.getName(), transDep.getParsedVersion(),
                    this.introducingTransitiveDependencyLibsToString(transitiveDependencyRequiredBy, transDep))
            );
            this.logProxy.uiWarn("Please add the missing transitive dependencies to your %s list.", MENUS_HOLDER_TITLE);
        }
        if (!missingTransitiveDependencies.isEmpty()) {
            this.logProxy.cliError("The following transitive dependencies are not listed in your project:%n");
            missingTransitiveDependencies.forEach(transDep -> this.logProxy.cliError(" - %s, possiblly version %s, introduced by: %s%n",
                    transDep.getName(), transDep.getParsedVersion(),
                    this.introducingTransitiveDependencyLibsToString(transitiveDependencyRequiredBy, transDep))
            );
            this.logProxy.uiWarn("Please add the missing transitive dependencies to your %s list.", MENUS_HOLDER_TITLE);
        }

        return librariesToInstall.size() + installedLibrariesCount - missingNotInstalledTransitiveDependencies.size();
    }

    private String introducingTransitiveDependencyLibsToString(
            Map<ContributedLibrary, Set<ContributedLibrary>> transDepAndIntroducers, ContributedLibrary transDep) {

        if (transDepAndIntroducers == null || transDep == null || !transDepAndIntroducers.containsKey(transDep)
                || CollectionUtils.isNullOrEmpty(transDepAndIntroducers.get(transDep))) {
            return "[ -no data- ]";
        }

        return "[ " + transDepAndIntroducers.get(transDep).stream().map(ContributedLibrary::getName)
                .collect(Collectors.joining(", ")) + " ]";
    }

    private boolean installLibraryFromURI(String libName, String libUri, Set<String> allRequiredLibNames) {
        if (libUri == null || "*".equals(libUri) || !libUri.contains("://")) return false;

        URL url;
        URI uri;

        try {
            uri = new URI(libUri);
            url = uri.toURL();
        } catch (URISyntaxException e) {
            return false;
        } catch (Exception e) {
            this.logProxy.cliError("Failed extracting URL from %s%n", libUri);
            return false;
        }

        if (uri.getScheme().toLowerCase().startsWith("http")) {

            boolean isZip = uri.getPath().toLowerCase().endsWith(".zip");
            if (!isZip && !uri.getPath().toLowerCase().endsWith(".git")) {
                this.logProxy.cliError("The external URL library must be a concrete ZIP file or GIT repository - '%s'! Skipping it!%n", libUri);
                return true;
            }

            this.logProxy.cliInfo("%s URI detected! Picked %s%n", (isZip ? "ZIP file" : "GIT repo"), libUri);

            FileUtils fileUtils = new FileUtils(logProxy);

            if (!isZip) {
                FileUtils.RepoLibDir repoLibDir = fileUtils.downloadGit(url, libName, "-libgit");
                if (repoLibDir != null && repoLibDir.dir != null) {
                    ExternLibFileInstaller<EditorConsole> installer = new ExternLibFileInstaller<EditorConsole>(this.logProxy);
                    if (installer.installZipOrDirWithZips(repoLibDir.dir)) {
                        installer.logSuccessfullyInstalledLib(libUri);
                        BaseNoGui.librariesIndexer.rescanLibraries();
                        logURILibNotInstalledOrUnlistedTransitiveDependencies(repoLibDir.resultingLibNameDir, installer, allRequiredLibNames);
                    }
                    fileUtils.delayedDirRemoval(30000, repoLibDir.dir.getParentFile());
                }
            } else {
                File tempFile = fileUtils.downloadZip(url, "-lib");
                if (tempFile != null) {
                    ExternLibFileInstaller<EditorConsole> installer = new ExternLibFileInstaller<EditorConsole>(this.logProxy);
                    if (installer.installZipOrDirWithZips(tempFile)) {
                        installer.logSuccessfullyInstalledLib(libUri);
                        BaseNoGui.librariesIndexer.rescanLibraries();
                        logURILibNotInstalledOrUnlistedTransitiveDependencies(libName, installer, allRequiredLibNames);
                    }
                    fileUtils.delayedFileRemoval(30000, tempFile);
                }
            }

            return true;
        }

        this.logProxy.cliInfo("URI detected! Picked %s%n", libUri);
        File f = new File(uri);

        ExternLibFileInstaller<EditorConsole> installer = new ExternLibFileInstaller<EditorConsole>(this.logProxy);
        if (installer.installZipOrDirWithZips(f)) {
            installer.logSuccessfullyInstalledLib(libUri);
            BaseNoGui.librariesIndexer.rescanLibraries();
            logURILibNotInstalledOrUnlistedTransitiveDependencies(libName, installer, allRequiredLibNames);
        }

        return true;
    }

    private void logURILibNotInstalledOrUnlistedTransitiveDependencies(
            String libName, ExternLibFileInstaller<EditorConsole> installer, Set<String> allRequiredLibNames) {

        String currBoardArch = Optional.ofNullable(BaseNoGui.getTargetBoard())
                .map(TargetBoard::getContainerPlatform)
                .map(TargetPlatform::getId)
                .orElse(null);
        if (TextUtils.isNullOrBlank(currBoardArch)) {
            String error = "Cannot determine the target board / platform / architecture!\nThis may indicate a missing or corrupted board package.";
            this.logProxy.cliErrorln(error);
            this.logProxy.uiError(error);
            return;
        }

        Set<String> notInstalledTransitiveDependencies = installer.getTransitiveDependencies().stream()
                .map(BaseNoGui.librariesIndexer.getIndex()::find)
                .filter(contributedLibraries -> !contributedLibraries.isEmpty() && contributedLibraries.stream()
                        .allMatch(cl -> !cl.isLibraryInstalled() && !cl.getArchitectures().isEmpty() && !cl.getArchitectures().contains("*")
                                && !cl.getArchitectures().contains(currBoardArch))
                )
                .map(contributedLibraries -> contributedLibraries.get(0).getName())
                .collect(Collectors.toSet());

        if (!notInstalledTransitiveDependencies.isEmpty()) {
            this.logProxy.cliError("%s depends transitively on the following not installed libraries:%n", libName);
            notInstalledTransitiveDependencies.forEach(td -> this.logProxy.cliErrorln(" - " + td));
            this.logProxy.uiWarn("Please add the missing transitive dependencies to your Drizzle list.");
        }

        Set<String> unlistedTransitiveDependencies = installer.getTransitiveDependencies().stream()
                .filter(missingLib -> !(allRequiredLibNames != null && (allRequiredLibNames.contains(missingLib)
                        || allRequiredLibNames.contains(missingLib.replace(' ', '_'))))
                )
                .collect(Collectors.toSet());
        if (!unlistedTransitiveDependencies.isEmpty()) {
            this.logProxy.cliError(
                    "%s depends transitively on the following unlisted in your %s settings libraries:%n", libName, MENUS_HOLDER_TITLE);
            unlistedTransitiveDependencies.forEach(td -> this.logProxy.cliErrorln(" - " + td));
            this.logProxy.uiWarn("Please add the missing transitive dependencies to your Drizzle list.");
        }
    }

    private int selectBoardOptions() {
        String source;
        try {
            source = SourceExtractor.loadSourceFromPrimarySketch(editor);
        } catch (IOException e) {
            this.logProxy.cliErrorln(e);
            this.logProxy.uiError(e.getMessage());
            return -1;
        }

        TargetPlatform targetPlatform = BaseNoGui.getTargetPlatform();
        TargetBoard targetBoard = BaseNoGui.getTargetBoard();

        String targetPlatformName = (targetPlatform != null ? targetPlatform.getId() : null);
        String targetBoardName = (targetBoard != null ? targetBoard.getName() : null);
        String targetBoardId = (targetBoard != null ? targetBoard.getId() : null);

        List<SourceExtractor.BoardSettings> settingsToClick =
                this.sourceExtractor.dependentBoardClickableSettingsFromMainSketchSource(source).stream()
                        .filter(boardSettings -> boardSettings.suitsRequirements(targetPlatformName, targetBoardId)
                                || boardSettings.suitsRequirements(targetPlatformName, targetBoardName))
                        .collect(Collectors.toList());

        int clickedItems = 0;

        List<JMenu> jMenus = this.uiLocator.customMenusForCurrentlySelectedBoard();

        for (SourceExtractor.BoardSettings bs : settingsToClick) {
            for (List<String> menuPath : bs.clickableOptions) {

                List<String> parents = (menuPath.size() > 1 ? menuPath.subList(0, menuPath.size() - 1) : Collections.emptyList());
                String menuItem = (!parents.isEmpty() ? menuPath.get(menuPath.size() - 1) : menuPath.get(0));

                JMenuItem me = this.uiLocator.walkTowardsMenuItem(jMenus, parents, (targetMenuName, currMenu) -> {
                       if (!currMenu.isEnabled() && !currMenu.isVisible()) {
                           return false;
                       }

                        if (targetPlatformName != null) {
                            Object platform = currMenu.getClientProperty("platform");
                            if (platform != null && !platform.toString().startsWith(targetPlatformName + "_")) {
                                return false;
                            }
                        }

                        String currMenuName = currMenu.getText();
                        if (currMenuName != null) {
                            currMenuName = currMenuName.split(":", 2)[0];
                            return targetMenuName.equals(currMenuName);
                        }

                        return false;
                    },
                        candidateMenuItem -> candidateMenuItem.isVisible() && candidateMenuItem.isEnabled()
                                && menuItem.equals(candidateMenuItem.getText())
                );

                if (me != null) {
                    System.out.printf("Clicking on %s->%s%n", String.join("->", parents), me.getText());
                    me.doClick();
                    ++clickedItems;
                }
            }
        }

        return clickedItems;
    }

    private String makeAutogenHeadingText() {
        return String.format("/**\n * Automatically generated by Drizzle %s dependency helper tool, based on the selected"
                + "\n * at %s board options in Arduino IDE's UI. To apply them make sure this file is saved, then click on"
                + "\n * Tools -> %s -> %s. To obtain Drizzle visit: %s"
                + "\n *", DrizzleCLI.version(), Instant.now().toString(), MENUS_HOLDER_TITLE, MENU_APPLY_MARKERS_TITLE, UpdateUtils.webUrl());
    }

    private List<String> autogenDependencyMarkers(final ActionEvent e, MutableBoolean hadCompilationIssuesFlag) {
        final Map<String, String> libs = new LinkedHashMap<>();
        Pattern libsPattern = Pattern.compile(".*-> candidates: \\[([^\\]]+)\\].*");

        Optional<EditorStatus> editorStatus = uiLocator.editorStatus();
        editorStatus.ifPresent(es -> es.progress("Gathering dependency information..."));

        Consumer<Integer> progressVisualizer = editorStatus.map(es -> new Consumer<Integer>() {
            @Override
            public void accept(final Integer integer) {
                es.progressUpdate(integer);
            }
        }).orElse(null);

        try {
            new CompilationInvoker(editor, logProxy, msg -> {
                Matcher matcher = libsPattern.matcher(msg);

                if (matcher.matches()) {
                    Stream.of(matcher.group(1).split(" "))
                            .map(libName -> TextUtils.ltrim(libName, "["))
                            .map(libName -> TextUtils.rtrim(libName, "]"))
                            .sorted((lib1, lib2) -> {
                                String v1 = TextUtils.extractIf(lib1.split("@", 2), 1, TextUtils::isNotNullOrBlank, "*");
                                String v2 = TextUtils.extractIf(lib2.split("@", 2), 1, TextUtils::isNotNullOrBlank, "*");
                                return -Version.fromString(v1).compareTo(Version.fromString(v2));
                            })
                            .findFirst()
                            .ifPresent(lib -> {
                                String[] nameAndVersion = lib.split("@", 2);
                                String ver = TextUtils.extractIf(nameAndVersion, 1, TextUtils::isNotNullOrBlank, "*");
                                if (!"*".equals(ver)) {
                                    ver = "^" + TextUtils.trim(ver, " ");
                                }
                                libs.put(TextUtils.trim(nameAndVersion[0], " "), ver);
                            });
                }
            }, progressVisualizer, this.normalCompilationHook.getBeforeHook(), this.normalCompilationHook.getAfterHook()).compile();
        } catch (RunnerException runnerException) {
            hadCompilationIssuesFlag.set(true);
            logProxy.cliErrorln(runnerException);
        } finally {
            editorStatus.ifPresent(EditorStatus::unprogress);
        }

        return libs.entrySet()
                .stream()
                .map(entry -> String.format("%s %s::%s", SourceExtractor.DEPENDSON_MARKER, entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }

    private List<String> autogenBoardAndBoardSettingsMarkers(final ActionEvent e) {
        // a semi-naive implementation that should do the job for now

        List<String> result = new ArrayList<>();

        JMenu boardMenu = this.uiLocator.boardsMenu().orElse(null);

        String boardSettings = this.uiLocator.customMenusForCurrentlySelectedBoard().stream()
                .filter(m -> m != boardMenu)
                .map(m -> {
                    String[] labelAndValue = TextUtils.labelAndUnquotedValue(m.getText());
                    if (labelAndValue.length == 0) return null;

                    return Stream.of(labelAndValue)
                            .filter(TextUtils::isNotNullOrBlank)
                            .collect(Collectors.joining("->"));

                })
                .filter(TextUtils::isNotNullOrBlank)
                .collect(Collectors.joining("||"));

        String providerPackageName = PreferencesData.get("target_package", null);
        String platformName = PreferencesData.get("target_platform", null);
        String boardName = PreferencesData.get("board", TextUtils.unquotedValueFromLabelPair(boardMenu != null ? boardMenu.getText() : null));

        if (TextUtils.isNotNullOrBlank(boardName) && TextUtils.isNotNullOrBlank(platformName)) {
            String board = String.format("%s %s%s::%s", SourceExtractor.BOARDNAME_MARKER,
                    (TextUtils.isNullOrBlank(providerPackageName) ? "" : providerPackageName + "::"), platformName, boardName);
            result.add(board);
        } else {
            this.logProxy.cliError("Cannot automatically generate %s marker%n", SourceExtractor.BOARDNAME_MARKER);
        }

        if (TextUtils.isNotNullOrBlank(boardSettings)) {
            boardSettings = String.format("%s %s::%s::%s", SourceExtractor.BOARDSETTINGS_MARKER,
                    (TextUtils.isNullOrBlank(platformName) ? "*" : platformName),
                    (TextUtils.isNullOrBlank(boardName) ? "*" : boardName),
                    boardSettings
            );
            result.add(boardSettings);
        } else {
            this.logProxy.cliWarn("No suitable clickable menus found for %s marker%n", SourceExtractor.BOARD_SETTINGS);
        }

        String boardManager = "";
        if (TextUtils.isNotNullOrBlank(platformName)) {
            Map<String, ContributedPlatform> possiblePlatforms = BaseNoGui.indexer.getPackages().stream()
                    .map(ContributedPackage::getPlatforms)
                    .flatMap(List::stream)
                    .filter(cp -> !cp.isBuiltIn() && cp.isInstalled() && platformName.equals(cp.getArchitecture())
                            && TextUtils.isNotNullOrBlank(cp.getUrl()))
                    .collect(Collectors.toMap(ContributedPlatform::getVersion, Function.identity()));

            if (!possiblePlatforms.isEmpty()) {
                String s = SemVer.maxSatisfying(possiblePlatforms.keySet(), "*");
                boardManager = String.format("%s %s::%s", SourceExtractor.BOARDMANAGER_MARKER, platformName, s);
                result.add(boardManager);
            }
        }

        if (TextUtils.isNullOrBlank(boardManager)) {
            this.logProxy.cliInfo("Skipped the generation of %s marker%n", SourceExtractor.BOARDMANAGER_MARKER);
        }

        return result;
    }

    private EditorTab getPrimarySketchTabIfOpened() {
        SketchFile primarySketch = this.editor.getSketch().getPrimaryFile();
        EditorTab currentTab = this.editor.getCurrentTab();
        if (currentTab.getSketchFile() != primarySketch) {
            this.logProxy.uiError("To generate Drizzle markers select the %s tab", primarySketch.getPrettyName());
            return null;
        }
        return currentTab;
    }

    private void insertCommentBlockWithMarkersAtStartOfTheCurrentTab(List<String> commentLines) {
        if (!commentLines.isEmpty()) {
            EditorTab currentTab = getPrimarySketchTabIfOpened();
            if (currentTab == null) return;

            String comment = TextUtils.concatenate(TextUtils::isNotNullOrBlank, s -> String.format("\n * %s", s),
                    commentLines.toArray(new String[commentLines.size()]));

            StringBuilder sb = new StringBuilder(makeAutogenHeadingText())
                    .append(comment)
                    .append("\n */\n\n")
                    .append(currentTab.getText());

            currentTab.setScrollPosition(0);
            currentTab.setText(sb.toString());
            currentTab.invalidate();

            this.logProxy.uiInfo("Markers auto-generation is done. Don't forget to save the changes.");
        }
    }
}