package com.github.zhgzhg.drizzle.utils.arduino;

import com.github.zhgzhg.drizzle.utils.file.FileUtils;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.jar.JarFile;

public class ArduinoIDEToolsInstaller {
    private final Path ideToolsDir;
    private final LogProxy logProxy;

    public ArduinoIDEToolsInstaller(LogProxy logProxy) {
        this.logProxy = logProxy;
        this.ideToolsDir = Paths.get(URI.create("file://" + this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath()))
                .getParent()
                .getParent()
                .getParent()
                .toAbsolutePath();
    }

    public String extractInstalledToolVersion(String toolName) {

        FileUtils fileUtils = new FileUtils(this.logProxy);

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

        FileUtils fileUtils = new FileUtils(this.logProxy);
        File tempFile = fileUtils.downloadZip(toolLocation, "-ardtool");

        if (tempFile == null || !tempFile.exists()) {
            this.logProxy.cliError("Failed downloading '%s'%n", toolLocation);
            return false;
        }

        boolean isSuccess = false;

        ZipFile zipFile = new ZipFile(tempFile);
        if (zipFile.isValidZipFile()) {
            Path path = fileUtils.caseInsensitiveShallowDirSearch(this.ideToolsDir, toolName);
            if (path != null) {
                fileUtils.removeDir(path);
            }
            try {
                zipFile.extractAll(this.ideToolsDir.toString());
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
