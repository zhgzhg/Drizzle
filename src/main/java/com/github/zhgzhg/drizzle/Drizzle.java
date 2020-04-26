package com.github.zhgzhg.drizzle;

import cc.arduino.contributions.GPGDetachedSignatureVerifier;
import cc.arduino.contributions.ProgressListener;
import cc.arduino.contributions.libraries.ContributedLibrary;
import cc.arduino.contributions.libraries.LibrariesIndex;
import cc.arduino.contributions.libraries.LibraryInstaller;
import cc.arduino.contributions.packages.ContributedPackage;
import cc.arduino.contributions.packages.ContributedPlatform;
import cc.arduino.contributions.packages.ContributionInstaller;
import com.github.gundy.semver4j.SemVer;
import com.github.zhgzhg.drizzle.utils.LogProxy;
import com.github.zhgzhg.drizzle.utils.ProgressPrinter;
import com.github.zhgzhg.drizzle.utils.SourceExtractor;
import processing.app.Base;
import processing.app.BaseNoGui;
import processing.app.Editor;
import processing.app.EditorConsole;
import processing.app.PreferencesData;
import processing.app.debug.TargetBoard;
import processing.app.tools.Tool;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Drizzle implements Tool {

    public static final String BOARD_MENU_PREFIX_LABEL = "Board:";
    private Editor editor;
    private ContributionInstaller contributionInstaller;
    private LibrariesIndex librariesIndex;
    private LibraryInstaller libraryInstaller;
    private SourceExtractor sourceExtractor;
    private final GPGDetachedSignatureVerifier gpgDetachedSignatureVerifier = new GPGDetachedSignatureVerifier();

    private ProgressListener progressListener;
    private ProgressPrinter progressPrinter;
    private LogProxy logProxy;

    @Override
    public void init(final Editor editor) {
        this.editor = editor;
        this.logProxy = new LogProxy() {
            @Override
            public void uiError(final String format, final Object... params) { editor.statusError(String.format(format, params)); }

            @Override
            public void uiError(final Throwable t) { Base.showError("Error", t.getMessage(), t); }

            @Override
            public void uiInfo(final String format, final Object... params) { editor.statusNotice(String.format(format, params)); }
        };
        this.progressPrinter = new ProgressPrinter(logProxy);
        this.sourceExtractor = new SourceExtractor(logProxy);
        this.progressListener = progress -> progressPrinter.progress();

        this.librariesIndex = BaseNoGui.librariesIndexer.getIndex();
        this.contributionInstaller = new ContributionInstaller(BaseNoGui.getPlatform(), gpgDetachedSignatureVerifier);
        this.libraryInstaller = new LibraryInstaller(BaseNoGui.getPlatform(), gpgDetachedSignatureVerifier);
    }

    @Override
    public String getMenuTitle() {
        return "Bulk Resolve Marked Dependencies";
    }

    @Override
    public void run() {
        Optional<EditorConsole> console = Stream.of(editor.getContentPane().getComponents())
                .filter(c -> c instanceof JPanel)
                .flatMap(c -> Stream.of(((JPanel) c).getComponents()))
                .filter(c -> c instanceof Box)
                .flatMap(c -> Stream.of(((Box) c).getComponents()))
                .filter(c -> c instanceof JSplitPane)
                .flatMap(c -> Stream.of(((JSplitPane) c).getComponents()))
                .filter(c -> c instanceof JPanel)
                .flatMap(c -> Stream.of(((JPanel) c).getComponents()))
                .filter(c -> c instanceof EditorConsole)
                .map(c -> (EditorConsole) c)
                .findFirst();
        console.ifPresent(EditorConsole::clear);

        this.logProxy.uiInfo("                                                                                                                                                                                                               ");

        new Thread(() -> {
            int installedBoardsCount = installBoards();
            if (installedBoardsCount == 0) {
                this.logProxy.cliErrorln("No platform definitions managed by " + SourceExtractor.BOARDMANAGER_MARKER +
                        " marker in the main sketch were found");
            }

            int installedLibsCount = installLibraries();
            if (installedLibsCount < 0) {
                String err = "Couldn't install some/any libraries - managed by marker " + SourceExtractor.DEPENDSON_MARKER +
                        " in the main sketch!";
                this.logProxy.cliErrorln(err);
                this.logProxy.uiInfo(err);
            }

            if (selectBoard() == 0) {
                this.logProxy.cliInfoln("No default board specified with " + SourceExtractor.BOARDNAME_MARKER +
                        " marker in the main sketch was found");
            }
        }).start();
    }

    private int selectBoard() {
        String source;
        try {
            source = SourceExtractor.loadSourceFromPrimarSketch(editor);
        } catch (IOException e) {
            this.logProxy.cliErrorln(e);
            this.logProxy.uiError(e.getMessage());
            return -1;
        }

        SourceExtractor.Board board = sourceExtractor.dependentBoardFromMainSketchSource(source);
        if (board == null) return 0;

        TargetBoard targetBoard = BaseNoGui.indexer.getPackages()
                .stream()
                .filter(contributedPackage -> contributedPackage.getPlatforms()
                        .stream()
                        .anyMatch(contributedPlatform -> board.platform.equals(contributedPlatform.getName()))
                )
                .flatMap(contributedPackage -> BaseNoGui.getTargetPackage(contributedPackage.getName()).platforms().stream())
                .flatMap(targetPlatform -> targetPlatform.getBoards().entrySet().stream())
                .filter(idTargetBoardEntry -> board.name.equals(idTargetBoardEntry.getValue().getName()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);

        if (targetBoard == null) {
            this.logProxy.cliErrorln("Failed to pick board based on " + board);
            return -1;
        }

        BaseNoGui.selectBoard(targetBoard);
        BaseNoGui.onBoardOrPortChange();
        this.logProxy.cliInfo("Selected board %s%n", targetBoard.getName());
        try {
            Base.INSTANCE.getBoardsCustomMenus().stream()
                    .filter(menu -> menu.getText().startsWith(BOARD_MENU_PREFIX_LABEL))
                    .limit(1)
                    .forEach(b -> {
                        Container parent = b.getParent();
                        if (parent != null)
                            parent.remove(b);
                    });
            Base.INSTANCE.rebuildBoardsMenu();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }

        try {
            // Unlikely
            Base.INSTANCE.getBoardsCustomMenus().stream()
                    .filter(menu -> menu.getText().startsWith(BOARD_MENU_PREFIX_LABEL))
                    .flatMap(menu -> Stream.of(menu.getMenuComponents()))
                    .filter(component -> component instanceof JMenuItem && ((JMenuItem) component).getText().equals(board.name))
                    .map(component -> (JMenuItem) component)
                    .forEach(item -> {
                        this.logProxy.cliInfo("Re-selecting board %s%n", item.getText());
                        item.doClick();
                    });
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }

        return 1;
    }

    private int installBoards() {
        String source;
        try {
            source = SourceExtractor.loadSourceFromPrimarSketch(editor);
        } catch (IOException e) {
            this.logProxy.cliErrorln(e);
            this.logProxy.uiError(e.getMessage());
            return -1;
        }

        SourceExtractor.BoardManager bmSettings = this.sourceExtractor.dependentBoardManagerFromMainSketchSource(source);
        if (bmSettings == null) return -1;

        String boardUrlsCsv = PreferencesData.get(cc.arduino.Constants.PREF_BOARDS_MANAGER_ADDITIONAL_URLS, "");
        if (bmSettings.url != null && !boardUrlsCsv.toLowerCase().contains(bmSettings.url)) {
            PreferencesData.set(cc.arduino.Constants.PREF_BOARDS_MANAGER_ADDITIONAL_URLS, boardUrlsCsv.concat(",").concat(bmSettings.url));
        }

        this.logProxy.cliInfo("Updating platform definitions list...");
        this.progressPrinter.begin(1, -1, 100, "...");
        List<String> downloadedPackageIndexFiles = this.contributionInstaller.updateIndex(this.progressListener);
        try {
            this.contributionInstaller.deleteUnknownFiles(downloadedPackageIndexFiles);
        } catch (IOException e) {
            e.printStackTrace(System.err);
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
        if (chosenVersion != null && !chosenVersion.isEmpty()) {
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
            Base.INSTANCE.getBoardsCustomMenus().stream()
                    .filter(menu -> menu.getText().startsWith(BOARD_MENU_PREFIX_LABEL))
                    .limit(1)
                    .forEach(b -> {
                        Container parent = b.getParent();
                        if (parent != null)
                            parent.remove(b);
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

        List<ContributedLibrary> libraries = librariesIndex.getLibraries();
        if (libraries == null || libraries.isEmpty()) {
            return Collections.emptyList();
        }

        return libraries;
    }

    private int installLibraries() {
        String source;
        try {
             source = SourceExtractor.loadSourceFromPrimarSketch(editor);
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

        for (Map.Entry<String, String> entry  : requiredLibs.entrySet()) {
            String libName = entry.getKey();
            String libVer = entry.getValue();

            List<ContributedLibrary> installCandidates =
                    availableLibraries.stream().filter(lib -> libName.equals(lib.getName())).collect(Collectors.toList());
            List<String> installCandidateVersions =
                    installCandidates.stream().map(ContributedLibrary::getParsedVersion).collect(Collectors.toList());

            this.logProxy.cliInfo("%s candidates: %s, required: %s%n", libName, installCandidateVersions.toString(), libVer);
            String chosenVersion = SemVer.maxSatisfying(installCandidateVersions, libVer);
            if (chosenVersion != null && !chosenVersion.isEmpty()) {
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

            Stream.of(editor.getContentPane().getParent().getComponents())
                    .filter(c -> c instanceof JMenuBar)
                    .map(jb -> ((JMenuBar) jb).getMenu(2).getMenuComponent(6)) // a.k.a "Sketch/Include Library"
                    .filter(jm -> jm instanceof JMenu)
                    .map(jm -> (JMenu) jm)
                    .findFirst()
                    .ifPresent(im -> Base.INSTANCE.rebuildImportMenu(im));

            Stream.of(editor.getContentPane().getParent().getComponents())
                    .filter(c -> c instanceof JMenuBar)
                    .map(jb -> ((JMenuBar) jb).getMenu(0).getMenuComponent(4)) // a.k.a "File/Examples"
                    .map(jm -> (JMenu) jm)
                    .findFirst()
                    .ifPresent(em -> Base.INSTANCE.rebuildExamplesMenu(em));

        } catch (Exception e) {
            this.logProxy.cliErrorln(e);
            this.logProxy.uiError(e.getMessage());
            return -1;
        }
        this.logProxy.cliInfoln(" done:");
        librariesToInstall.forEach(l -> logProxy.cliInfo("  %s %s%n", l.getName(), l.getParsedVersion()));

        return librariesToInstall.size();
    }
}
