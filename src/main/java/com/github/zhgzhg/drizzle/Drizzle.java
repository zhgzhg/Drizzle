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
import com.github.zhgzhg.drizzle.utils.arduino.ExternLibFileInstaller;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.log.ProgressPrinter;
import com.github.zhgzhg.drizzle.utils.source.SourceExtractor;
import processing.app.Base;
import processing.app.BaseNoGui;
import processing.app.Editor;
import processing.app.EditorConsole;
import processing.app.I18n;
import processing.app.PreferencesData;
import processing.app.debug.TargetBoard;
import processing.app.debug.TargetPackage;
import processing.app.debug.TargetPlatform;
import processing.app.tools.Tool;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
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
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Drizzle implements Tool {

    public static final String BOARD_MENU_PREFIX_LABEL = "Board: ";
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
                        " marker in the main sketch file was found");
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

        TargetPlatform targetPlatform = targetBoard.getContainerPlatform();
        TargetPackage targetPackage = targetPlatform.getContainerPackage();
        if (PreferencesData.get("target_package", "").equals(targetPackage.getId())
                && PreferencesData.get("target_platform", "").equals(targetPlatform.getId())
                && PreferencesData.get("board", "").equals(targetBoard.getId())) {
            this.logProxy.cliInfo("Board %s is already selected%n", targetBoard.getName());
            return -1;
        }

        try {
            Base.INSTANCE.getBoardsCustomMenus()
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
                    .filter(menu -> menu.getText().startsWith(I18n.tr(BOARD_MENU_PREFIX_LABEL)))
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

        return librariesToInstall.size() + installedLibrariesCount;
    }

    private boolean installLibraryFromURI(String libUri) {
        URL url;
        URI uri;

        try {
            uri = new URI(libUri);
            url = uri.toURL();
        } catch (URISyntaxException e) {
            return false;
        } catch (MalformedURLException e) {
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
                if (bytesTransferred < 1)
                    throw new IOException("File of 0 bytes size");

                ExternLibFileInstaller installer = new ExternLibFileInstaller(this.logProxy);
                if (installer.installZipOrDirWithZips(tempFile)) {
                    installer.logSuccessfullyInstalledLib(libUri);
                }

            } catch (IOException e) {
                this.logProxy.cliError("Failed transferring %s:%n", libUri);
                this.logProxy.cliErrorln(e);

            }

            new Thread(() -> {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                removeFile(tempFile);
            }).start();

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

    private void removeFile(File tempFile) {
        try {
            Files.deleteIfExists(tempFile.toPath());
        } catch (IOException e) {
            this.logProxy.cliErrorln(e);
        }
    }
}