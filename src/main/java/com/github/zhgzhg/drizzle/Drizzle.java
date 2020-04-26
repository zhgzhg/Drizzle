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
import processing.app.Base;
import processing.app.BaseNoGui;
import processing.app.Editor;
import processing.app.EditorConsole;
import processing.app.PreferencesData;
import processing.app.debug.TargetBoard;
import processing.app.tools.Tool;

import javax.swing.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Drizzle implements Tool {

    private Editor editor;
    private ContributionInstaller contributionInstaller;
    private LibrariesIndex librariesIndex;
    private LibraryInstaller libraryInstaller;
    private SourceExtractor sourceExtractor;
    private final GPGDetachedSignatureVerifier gpgDetachedSignatureVerifier = new GPGDetachedSignatureVerifier();

    private final ProgressListener progressListener = progress -> {
        if (((long) progress.getProgress()) % 5 == 0) System.out.print("...");
        if (((long) progress.getProgress()) % 8400 == 0) System.out.println();
    };

    @Override
    public void init(final Editor editor) {
        this.editor = editor;
        this.librariesIndex = BaseNoGui.librariesIndexer.getIndex();
        this.contributionInstaller = new ContributionInstaller(BaseNoGui.getPlatform(), gpgDetachedSignatureVerifier);
        this.libraryInstaller = new LibraryInstaller(BaseNoGui.getPlatform(), gpgDetachedSignatureVerifier);
        this.sourceExtractor = new SourceExtractor(editor);
    }

    @Override
    public String getMenuTitle() {
        return "Bulk Resolve Marked Dependencies";
    }

    @Override
    public void run() {
        Stream.of(editor.getContentPane().getComponents())
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
                .forEach(EditorConsole::clear);

        editor.statusNotice("                                                                                                                                                                                                               ");

        SwingUtilities.invokeLater(() -> {
            int installedBoardsCount = installBoards();
            if (installedBoardsCount == 0) {
                System.out.println("No platform definitions managed by " + SourceExtractor.BOARDMANAGER_MARKER +
                        " marker in the main sketch were found");
            }

            int installedLibsCount = installLibraries();
            if (installedLibsCount < 0) {
                String err = "Couldn't install some/any libraries - managed by marker " + SourceExtractor.DEPENDSON_MARKER +
                        " in the main sketch!";
                System.err.println(err);
                editor.statusNotice(err);
            }

            if (selectBoard() == 0) {
                System.out.println(
                        "No default board specified with " + SourceExtractor.BOARDNAME_MARKER + " marker in the main sketch was found");
            }
        });
    }

    private int selectBoard() {
        SourceExtractor.Board board = sourceExtractor.dependentBoardFromMainSketchSource();
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
                .orElseGet(null);

        if (targetBoard == null) {
            System.err.println("Failed to pick board based on " + board);
            return -1;
        }

        BaseNoGui.selectBoard(targetBoard);
        BaseNoGui.onBoardOrPortChange();
        try {
            Base.INSTANCE.getBoardsCustomMenus().stream()
                    .filter(menu -> menu.getText().startsWith("Board:"))
                    .flatMap(menu -> Stream.of(menu.getMenuComponents()))
                    .filter(component -> component instanceof JMenuItem && ((JMenuItem) component).getText().equals(board.name))
                    .map(component -> (JMenuItem) component)
                    .forEach(item -> item.doClick());
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }

        return 1;
    }

    private int installBoards() {
        SourceExtractor.BoardManager bmSettings = this.sourceExtractor.dependentBoardManagerFromMainSketchSource();
        if (bmSettings == null) return -1;

        String boardUrlsCsv = PreferencesData.get(cc.arduino.Constants.PREF_BOARDS_MANAGER_ADDITIONAL_URLS, "");
        //Base.INSTANCE.getEditors().stream().map(editor -> editor.updateKeywords())

        if (bmSettings.url != null && !boardUrlsCsv.toLowerCase().contains(bmSettings.url)) {
            PreferencesData.set(cc.arduino.Constants.PREF_BOARDS_MANAGER_ADDITIONAL_URLS, boardUrlsCsv.concat(",").concat(bmSettings.url));
        }

        System.out.print("Updating platform definitions list...");
        List<String> downloadedPackageIndexFiles = this.contributionInstaller.updateIndex(this.progressListener);
        try {
            this.contributionInstaller.deleteUnknownFiles(downloadedPackageIndexFiles);
        } catch (IOException e) {
            e.printStackTrace(System.err);
            return 0;
        }
        System.out.println("\nDone!");

        System.out.println("Installing platform...");

        List<ContributedPlatform> possiblePlatforms = BaseNoGui.indexer.getPackages().stream()
                .map(ContributedPackage::getPlatforms)
                .flatMap(List::stream)
                .filter(p -> bmSettings.platform.equals(p.getName()))
                .collect(Collectors.toList());

        List<String> candidateVersions = possiblePlatforms.stream().map(ContributedPlatform::getParsedVersion).collect(Collectors.toList());

        System.out.printf("%s candidates: %s, required: %s%n", bmSettings.platform, candidateVersions.toString(), bmSettings.version);

        String chosenVersion = SemVer.maxSatisfying(candidateVersions, bmSettings.version);
        if (chosenVersion != null && !chosenVersion.isEmpty()) {
            int platfIndex = candidateVersions.indexOf(chosenVersion);
            ContributedPlatform platformToInstall = possiblePlatforms.get(platfIndex);
            System.out.printf("Picked %s%n", platformToInstall.getParsedVersion());

            boolean refreshUI = false;

            for (int i = 0; i < possiblePlatforms.size(); ++i) {
                if (i == platfIndex) continue;
                ContributedPlatform previouslyInstalled = possiblePlatforms.get(i);
                if (!previouslyInstalled.isBuiltIn() && previouslyInstalled.isInstalled()) {
                    this.contributionInstaller.remove(previouslyInstalled);
                    refreshUI = true;
                }
            }

            if (!platformToInstall.isInstalled()) {
                System.out.print("Installing...");
                try {
                    this.contributionInstaller.install(platformToInstall, this.progressListener);
                    refreshUI = true;
                    System.out.println(" done!");
                } catch (Exception e) {
                    System.err.println();
                    e.printStackTrace(System.err);
                    editor.statusError(e);
                }
            }

            if (refreshUI) {
                try {
                    BaseNoGui.initPackages();
                    Base.INSTANCE.rebuildBoardsMenu();
                    Base.INSTANCE.getProgrammerMenus();
                    Base.INSTANCE.onBoardOrPortChange();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        } else {
            System.err.printf("Failed to pick version for platform %s, expression %s%n", bmSettings.platform, bmSettings.version);
        }

        return 1;
    }

    private List<ContributedLibrary> loadAvailableLibraries() {
        try {
            System.out.print("Updating library info...");
            libraryInstaller.updateIndex(progressListener);
            System.out.println("\nDone!");
        } catch (Exception e) {
            System.err.println("error!\n" + e.getMessage());
            BaseNoGui.showError("Error Updating Packages Info", e.getMessage(), e);
        }

        List<ContributedLibrary> libraries = librariesIndex.getLibraries();
        if (libraries == null || libraries.isEmpty()) {
            return Collections.emptyList();
        }

        return libraries;
    }

    private int installLibraries() {
        Map<String, String> requiredLibs = this.sourceExtractor.dependentLibsFromMainSketchSource();
        if (requiredLibs.isEmpty()) {
            return 0;
        }

        List<ContributedLibrary> availableLibraries = loadAvailableLibraries();
        if (availableLibraries.isEmpty()) {
            System.err.println("No available libraries were found!");
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

            System.out.printf("%s candidates: %s, required: %s%n", libName, installCandidateVersions.toString(), libVer);
            String chosenVersion = SemVer.maxSatisfying(installCandidateVersions, libVer);
            if (chosenVersion != null && !chosenVersion.isEmpty()) {
                int libIndex = installCandidateVersions.indexOf(chosenVersion);
                ContributedLibrary l = installCandidates.get(libIndex);
                System.out.printf("Picked %s%n", l.getParsedVersion());
                librariesToInstall.add(l);
            } else {
                System.err.printf("Failed to pick version for library %s, expression %s%n", libName, libVer);
            }
        }

        System.out.print("Installing libraries...");
        try {
            this.libraryInstaller.install(librariesToInstall, progressListener);
        } catch (Exception e) {
            System.err.println(e);
            editor.statusError(e);
            return -1;
        }
        System.out.println("\nDone:");

        librariesToInstall.forEach(l -> System.out.printf("%s %s%n", l.getName(), l.getParsedVersion()));

        return librariesToInstall.size();
    }
}
