package com.github.zhgzhg.drizzle.utils.arduino;

import com.github.zhgzhg.drizzle.utils.file.FileUtils;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.FileHeader;
import processing.app.EditorConsole;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

public class ArduinoIDEToolsInstaller {
    private final Path drizzleJarLocation;
    private final Path ideToolsDir;
    private final LogProxy<EditorConsole> logProxy;
    private final List<Path> installedFiles = new ArrayList<>();

    public ArduinoIDEToolsInstaller(LogProxy<EditorConsole> logProxy) {
        this.logProxy = logProxy;
        this.drizzleJarLocation = Paths.get(URI.create("file://" + this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath()));
        this.ideToolsDir = this.drizzleJarLocation.getParent().getParent().getParent().toAbsolutePath();
    }

    /**
     * After updating Drizzle with {@link #installTool}: the jar Drizzle runs from, unless the update replaced it.
     * Removing the old tool folder leaves it behind on Windows, where it stays locked while any editor window has it
     * loaded; and next to the new jar, the IDE would load both.
     *
     * @return the jar to delete once the IDE has exited, or null
     */
    public Path replacedRunningJar() {
        if (!Files.exists(this.drizzleJarLocation)) return null;
        for (Path installed : this.installedFiles) {
            if (isSameFile(installed, this.drizzleJarLocation)) return null;
        }
        return this.drizzleJarLocation;
    }

    private static boolean isSameFile(final Path a, final Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            String x = a.toAbsolutePath().normalize().toString();
            String y = b.toAbsolutePath().normalize().toString();
            return File.separatorChar == '\\' ? x.equalsIgnoreCase(y) : x.equals(y);
        }
    }

    public String extractInstalledToolVersion(String toolName) {

        FileUtils<EditorConsole> fileUtils = new FileUtils<>(this.logProxy);

        List<Path> jars = fileUtils.listJarsInDir(this.ideToolsDir.resolve(toolName).resolve("tool"));
        if (jars.isEmpty()) return null;

        String lcToolName = toolName.toLowerCase();

        Path selectedJar = jars.get(0);
        for (Path p : jars) {
            if (p.getFileName().toString().toLowerCase().contains(lcToolName)) {
                selectedJar = p;
            }
        }

        String version = "";
        try (JarFile tool = new JarFile(selectedJar.toFile())) {
            if (tool.getManifest() != null && tool.getManifest().getMainAttributes() != null) {
                version = TextUtils.trim(tool.getManifest().getMainAttributes().getValue("Implementation-Version"), " ");
                if (version == null) version = "";
            }
        } catch (IOException e) {
            e.printStackTrace(this.logProxy.stderr());
            return "";
        }

        logProxy.cliInfo("Detected installed arduino tool %s::%s%n", toolName,
                TextUtils.returnAnyNotBlank(version, "unknown version"));

        return version;
    }


    public boolean installTool(String toolName, String toolLocation) {
        URL url = TextUtils.toURL(toolLocation, logProxy::cliErrorln);
        if (url != null) {
            return this.installTool(toolName, url);
        }
        return false;
    }

    public boolean installTool(String toolName, URL toolLocation) {
        if (TextUtils.isNullOrBlank(toolName) || toolLocation == null) {
            this.logProxy.cliError("Failed to install Arduino tool '%s'%n",
                    TextUtils.returnAnyNotBlank(toolName, "-no tool name-"));
            return false;
        }

        String protocol = toolLocation.getProtocol().toLowerCase();
        if ((!protocol.startsWith("http") && !protocol.startsWith("file")) || !toolLocation.getPath().toLowerCase().endsWith(".zip")) {
            this.logProxy.cliError("The external URL library must be a concrete ZIP file, and the protocol has to be 'http/https/file'"
                    + " - '%s' provided instead! Skipping it!%n", toolLocation);
            return false;
        }

        FileUtils<EditorConsole> fileUtils = new FileUtils<>(this.logProxy);
        File tempFile = fileUtils.downloadZip(toolLocation, "-ardtool");

        if (tempFile == null || !tempFile.exists()) {
            this.logProxy.cliError("Failed downloading '%s'%n", toolLocation);
            return false;
        }

        boolean isSuccess = false;
        this.installedFiles.clear();

        ZipFile zipFile = new ZipFile(tempFile);
        if (zipFile.isValidZipFile()) {
            Path path = fileUtils.caseInsensitiveShallowDirSearch(this.ideToolsDir, toolName);
            if (path != null) {
                fileUtils.removeDir(path);
            }
            try {
                zipFile.extractAll(this.ideToolsDir.toString());
                for (FileHeader header : zipFile.getFileHeaders()) {
                    if (!header.isDirectory()) this.installedFiles.add(this.ideToolsDir.resolve(header.getFileName()).normalize());
                }
                isSuccess = true;
            } catch (ZipException e) {
                this.logProxy.cliErrorln(e);
            }
        } else {
            this.logProxy.cliError("Got invalid ZIP archive from '%s'%n", toolLocation);
        }

        fileUtils.delayedFileRemoval(1000, tempFile);

        return isSuccess;
    }
}
