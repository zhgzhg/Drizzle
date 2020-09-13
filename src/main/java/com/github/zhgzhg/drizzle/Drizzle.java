package com.github.zhgzhg.drizzle;

import cc.arduino.contributions.GPGDetachedSignatureVerifier;
import cc.arduino.contributions.ProgressListener;
import cc.arduino.contributions.libraries.ContributedLibrary;
import cc.arduino.contributions.libraries.LibrariesIndex;
import cc.arduino.contributions.libraries.LibraryInstaller;
import cc.arduino.contributions.packages.ContributedPackage;
import cc.arduino.contributions.packages.ContributedPlatform;
import cc.arduino.contributions.packages.ContributionInstaller;
import cc.arduino.view.NotificationPopup;
import com.github.gundy.semver4j.SemVer;
import com.github.zhgzhg.drizzle.utils.arduino.ExternLibFileInstaller;
import com.github.zhgzhg.drizzle.utils.arduino.UILocator;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.log.ProgressPrinter;
import com.github.zhgzhg.drizzle.utils.source.SourceExtractor;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;
import com.github.zhgzhg.drizzle.utils.update.UpdateUtils;
import processing.app.Base;
import processing.app.BaseNoGui;
import processing.app.Editor;
import processing.app.EditorTab;
import processing.app.PreferencesData;
import processing.app.SketchFile;
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
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Drizzle implements Tool {

    public static final String MENU_TITLE = "Apply Drizzle @ Markers";
    public static final String MENU_AUTOGEN_TITLE = "Auto-generate @Board* Markers";

    private final GPGDetachedSignatureVerifier gpgDetachedSignatureVerifier = new GPGDetachedSignatureVerifier();
    private Editor editor;
    private ContributionInstaller contributionInstaller;
    private LibraryInstaller libraryInstaller;
    private SourceExtractor sourceExtractor;

    private ProgressListener progressListener;
    private ProgressPrinter progressPrinter;
    private LogProxy logProxy;
    private UILocator uiLocator;

    private JMenuItem boardAndSettingsGeneratorMenu = new JMenuItem(MENU_AUTOGEN_TITLE);

    @Override
    public void init(final Editor editor) {
        this.editor = editor;
        this.uiLocator = new UILocator(editor);
        this.logProxy = new LogProxy() {
            @Override
            public void uiError(final String format, final Object... params) { editor.statusError(String.format(format, params)); }

            @Override
            public void uiError(final Throwable t) { Base.showError("Error", t.getMessage(), t); }

            @Override
            public void uiWarn(final String format, final Object... params) { editor.statusNotice(String.format(format, params)); }

            @Override
            public void uiInfo(final String format, final Object... params) { editor.statusNotice(String.format(format, params)); }
        };
        this.progressPrinter = new ProgressPrinter(logProxy);
        this.sourceExtractor = new SourceExtractor(editor, logProxy);
        this.progressListener = progress -> progressPrinter.progress();

        this.contributionInstaller = new ContributionInstaller(BaseNoGui.getPlatform(), gpgDetachedSignatureVerifier);
        this.libraryInstaller = new LibraryInstaller(BaseNoGui.getPlatform(), gpgDetachedSignatureVerifier);

        this.boardAndSettingsGeneratorMenu.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                autogenBoardAndBoardSettingsMarkers(e);
            }
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
                            m.add(boardAndSettingsGeneratorMenu, index);
                            m.revalidate();
                            editor.removeComponentListener(this);
                        });

                SwingUtilities.invokeLater(() -> {
                    if (!UpdateUtils.isTheLatestVersion(logProxy)) {
                        NotificationPopup notification = UpdateUtils.createNewVersionPopupNotification(editor, logProxy);
                        notification.begin();
                    }
                });
            }
        });
    }

    @Override
    public String getMenuTitle() {
        return MENU_TITLE;
    }

    @Override
    public void run() {
        this.uiLocator.editorConsole().ifPresent(editorConsole -> {
            editorConsole.clear();
            this.logProxy.setEditorConsole(editorConsole);
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
                this.logProxy.cliInfo("No clickable board options specified with " + SourceExtractor.BOARDSETTINGS_MARKER
                        + " marker were matched");
            }

            drizzleMenu.ifPresent(dm -> dm.setEnabled(true));
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
                .filter(targtBrd -> board.name.equals(targtBrd.getName()))
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
                            board.name.equals(targtBrd.getName()))
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
        List<String> downloadedPackageIndexFiles = this.contributionInstaller.updateIndex(this.progressListener);
        try {
            this.contributionInstaller.deleteUnknownFiles(downloadedPackageIndexFiles);
        } catch (IOException e) {
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

        this.logProxy.cliInfo("%s candidates: %s, required: %s%n",
                bmSettings.platform, candidateVersions.toString(), bmSettings.version);

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
                this.logProxy.cliErrorln();
                this.logProxy.cliErrorln(e);
                this.logProxy.uiError(e.getMessage());
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

        Map<String, String> requiredLibs = this.sourceExtractor.dependentLibsFromMainSketchSource(source);
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

        for (Map.Entry<String, String> entry : requiredLibs.entrySet()) {
            String libName = entry.getKey();
            String libVer = entry.getValue();

            List<ContributedLibrary> installCandidates =
                    availableLibraries.stream().filter(lib -> libName.equals(lib.getName())).collect(Collectors.toList());
            List<String> installCandidateVersions =
                    installCandidates.stream().map(ContributedLibrary::getParsedVersion).collect(Collectors.toList());

            this.logProxy.cliInfo("%s candidates: %s, required: %s%n", libName, installCandidateVersions.toString(), libVer);

            if (installLibraryFromURI(libVer)) {
                ++installedLibrariesCount;
                continue;
            }

            String chosenVersion = SemVer.maxSatisfying(installCandidateVersions, libVer);
            if (TextUtils.isNotNullOrBlank(chosenVersion)) {
                int libIndex = installCandidateVersions.indexOf(chosenVersion);
                ContributedLibrary l = installCandidates.get(libIndex);
                this.logProxy.cliInfo("Picked library version %s%n", l.getParsedVersion());
                librariesToInstall.add(l);
            } else {
                this.logProxy.cliError("Failed to pick version for library %s, expression %s%n", libName, libVer);
            }
        }

        this.logProxy.cliInfo("Installing libraries...");
        try {
            this.progressPrinter.begin(1, 5, 80, ".");
            this.libraryInstaller.install(librariesToInstall, progressListener);

            this.uiLocator.sketchIncludeLibraryMenu().ifPresent(im -> Base.INSTANCE.rebuildImportMenu(im));
            this.uiLocator.filesExamplesMenu().ifPresent(em -> Base.INSTANCE.rebuildExamplesMenu(em));
        } catch (Exception e) {
            this.logProxy.cliErrorln(e);
            this.logProxy.uiError(e.getMessage());
            return -1;
        }
        this.logProxy.cliInfoln(" done:");
        librariesToInstall.forEach(l -> logProxy.cliInfo("  %s %s%n", l.getName(), l.getParsedVersion()));

        return librariesToInstall.size() + installedLibrariesCount;
    }

    private boolean installLibraryFromURI(String libUri) {
        if ("*".equals(libUri)) return false;

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
            if (!uri.getPath().toLowerCase().endsWith(".zip")) {
                this.logProxy.cliError("The extenal URL library must be a concrete ZIP file - '%s'! Skipping it!%n", libUri);
                return true;
            }

            this.logProxy.cliInfo("ZIP URI detected! Picked %s%n", libUri);

            File tempFile;
            try {
                tempFile = Files.createTempFile("ard-drizzle-ext-lib", ".zip").toFile();
                tempFile.deleteOnExit();
            } catch (IOException e) {
                this.logProxy.cliError("Failed creating temporary file for downloading the external library %s%n", libUri);
                return true;
            }

            try (ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
                    FileChannel fileOutputChannel = new FileOutputStream(tempFile).getChannel()) {

                long bytesTransferred = fileOutputChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
                if (bytesTransferred < 1) {
                    throw new IOException("File of 0 bytes size");
                }

                ExternLibFileInstaller installer = new ExternLibFileInstaller(this.logProxy);
                if (installer.installZipOrDirWithZips(tempFile)) {
                    installer.logSuccessfullyInstalledLib(libUri);
                }

            } catch (IOException e) {
                this.logProxy.cliError("Failed transferring %s:%n", libUri);
                this.logProxy.cliErrorln(e);
            }

            delayedFileRemoval(2000, tempFile);

            return true;
        }

        this.logProxy.cliInfo("URI detected! Picked %s%n", libUri);
        File f = new File(uri);

        ExternLibFileInstaller installer = new ExternLibFileInstaller(this.logProxy);
        if (installer.installZipOrDirWithZips(f)) {
            installer.logSuccessfullyInstalledLib(libUri);
        }

        return true;
    }

    private void delayedFileRemoval(long millis, File tempFile) {
        new Thread(() -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                e.printStackTrace(this.logProxy.stderr());
                Thread.currentThread().interrupt();
            } finally {
                try {
                    Files.deleteIfExists(tempFile.toPath());
                } catch (IOException e) {
                    this.logProxy.cliErrorln(e);
                }
            }
        }).start();
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

        List<SourceExtractor.BoardSettings> settingsToClick =
                this.sourceExtractor.dependentBoardClickableSettingsFromMainSketchSource(source).stream()
                        .filter(boardSettings -> boardSettings.suitsRequirements(targetPlatformName, targetBoardName))
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
        return String.format("/**%n * Automatically generated markers by Drizzle %s dependency helper tool, based on the selected"
                + "%n * at that moment board options in Arduino IDE's UI. To apply them make sure this file is saved, then click on"
                + "%n * Tools / %s. To obtain Drizzle visit: %s"
                + "%n *", UpdateUtils.version(), MENU_TITLE, UpdateUtils.webUrl());
    }

    private void autogenBoardAndBoardSettingsMarkers(final ActionEvent e) {
        // a semi-naive implementation that should do the job for now

        SketchFile primarySketch = this.editor.getSketch().getPrimaryFile();
        EditorTab currentTab = this.editor.getCurrentTab();
        if (currentTab.getSketchFile() != primarySketch) {
            this.logProxy.uiError("To generate Drizzle markers select the %s tab", primarySketch.getPrettyName());
            return;
        }

        String combinedMarkers = "";

        JMenu boardMenu = this.uiLocator.boardsMenu().orElse(null);

        String boardSettings = this.uiLocator.customMenusForCurrentlySelectedBoard().stream()
                .filter(m -> m != boardMenu)
                .map(m -> {
                    String[] labelAndValue = TextUtils.labelAndUnquotedValue(m.getText());
                    if (labelAndValue == null) return null;

                    return Stream.of(labelAndValue)
                            .filter(TextUtils::isNotNullOrBlank)
                            .collect(Collectors.joining("->"));

                })
                .filter(TextUtils::isNotNullOrBlank)
                .collect(Collectors.joining("||"));

        String providerPackageName = PreferencesData.get("target_package", null);
        String platformName = PreferencesData.get("target_platform", null);
        String boardName = TextUtils.unquotedValueFromLabelPair(boardMenu != null ? boardMenu.getText() : null);

        String board = "";

        if (TextUtils.isNotNullOrBlank(boardName) && TextUtils.isNotNullOrBlank(platformName)) {
            board = String.format("%s %s%s::%s", SourceExtractor.BOARDNAME_MARKER,
                    (TextUtils.isNullOrBlank(providerPackageName) ? "" : providerPackageName + "::"), platformName, boardName);
        } else {
            this.logProxy.cliError("Cannot automatically generate %s marker%n", SourceExtractor.BOARDNAME_MARKER);
        }

        if (TextUtils.isNotNullOrBlank(boardSettings)) {
            boardSettings = String.format("%s %s::%s::%s", SourceExtractor.BOARDSETTINGS_MARKER,
                    (TextUtils.isNullOrBlank(platformName) ? "*" : platformName),
                    (TextUtils.isNullOrBlank(boardName) ? "*" : boardName),
                    boardSettings
            );
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
            }
        }

        if (TextUtils.isNullOrBlank(boardManager)) {
            this.logProxy.cliInfo("Skipped the generation of %s marker", SourceExtractor.BOARDMANAGER_MARKER);
        }

        if (TextUtils.anyNotNullOrBlank(boardManager, board, boardSettings)) {
            String comment = TextUtils.concatenate(TextUtils::isNotNullOrBlank, s -> String.format("%n * %s", s), boardManager, board,
                    boardSettings);

            StringBuilder sb = new StringBuilder(makeAutogenHeadingText())
                    .append(comment)
                    .append(String.format("%n */%n%n"))
                    .append(currentTab.getText());

            currentTab.setScrollPosition(0);
            currentTab.setText(sb.toString());
            currentTab.invalidate();

            this.logProxy.uiInfo("Markers auto-generation is done. Don't forget to save the changes.");
        }
    }
}