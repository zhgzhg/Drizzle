package com.github.zhgzhg.drizzle.utils.arduino;

import com.github.zhgzhg.drizzle.utils.file.FileUtils;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;
import processing.app.EditorConsole;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.jar.JarFile;

public class ArduinoIDEToolsInstaller {
    private final Path drizzleJarLocation;
    private final Path ideToolsDir;
    private final LogProxy<EditorConsole> logProxy;

    public ArduinoIDEToolsInstaller(LogProxy<EditorConsole> logProxy) {
        this.logProxy = logProxy;
        this.drizzleJarLocation = Paths.get(URI.create("file://" + this.getClass().getProtectionDomain().getCodeSource().getLocation().getPath()));
        this.ideToolsDir = this.drizzleJarLocation.getParent().getParent().getParent().toAbsolutePath();
    }

    public void selfDestroyJarOnExit() {
        File drizzleJar = drizzleJarLocation.toFile();
        if (!drizzleJar.delete()) {
            drizzleJar.deleteOnExit();

            try {
                URLClassLoader loader = (URLClassLoader) this.getClass().getClassLoader();
                loader.close();
                if (!drizzleJar.delete()) {
                    drizzleJar.deleteOnExit();
                }
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
            }
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

    public void killJVM(long afterSeconds) {
        new Thread(() -> {
            try {
                for (long j = afterSeconds; j > 0; --j) {
                    try {
                        logProxy.uiWarn(".............%d............. seconds until Arduino IDE shuts down", j);
                    } catch (Exception e) {
                        // don't care
                    }
                    System.err.printf(".............%d.............%n", j);
                    Thread.sleep( 1000);
                }
                System.err.println("..........JVM STOP...........%n");
            } catch (InterruptedException ex) {
                // don't care
            }
            System.exit(1);
        }).start();
    }
}
