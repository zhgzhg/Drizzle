package com.github.zhgzhg.drizzle.utils.arduino;

import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;
import processing.app.BaseNoGui;
import processing.app.PreferencesData;
import processing.app.helpers.FileUtils;
import processing.app.helpers.filefilters.OnlyDirs;
import processing.app.tools.ZipDeflater;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class ExternLibFileInstaller<T> {
    private final LogProxy<T> logProxy;
    private final Set<String> transitiveDependencies;

    public ExternLibFileInstaller(LogProxy<T> logProxy) {
        this.logProxy = logProxy;
        this.transitiveDependencies = new LinkedHashSet<>();
    }

    public boolean installZipOrDirWithZips(File sourceFile) {
        File tmpFolder = null;

        try {
            // unpack ZIP
            if (!sourceFile.isDirectory()) {
                try {
                    tmpFolder = FileUtils.createTempFolder();
                    ZipDeflater zipDeflater = new ZipDeflater(sourceFile, tmpFolder);
                    zipDeflater.deflate();
                    File[] foldersInTmpFolder = tmpFolder.listFiles(new OnlyDirs());
                    if (foldersInTmpFolder == null || foldersInTmpFolder.length != 1) {
                        throw new IOException("Zip doesn't contain a library");
                    }
                    sourceFile = foldersInTmpFolder[0];
                } catch (IOException e) {
                    this.logProxy.cliErrorln(e);
                    return false;
                }
            }

            File libFolder = sourceFile;
            if (FileUtils.isSubDirectory(new File(PreferencesData.get("sketchbook.path")), libFolder)) {
                this.logProxy.cliErrorln("A subfolder of the current sketchbook is not a valid library");
                return false;
            }

            if (FileUtils.isSubDirectory(libFolder, new File(PreferencesData.get("sketchbook.path")))) {
                this.logProxy.cliErrorln("Cannot import a folder that contains the current sketchbook");
                return false;
            }

            String libName = libFolder.getName();
            if (!BaseNoGui.isSanitaryName(libName)) {
                this.logProxy.cliError("Cannot use library '%s'.%nLibrary names must contain only basic letters and numbers.%n" +
                        "(ASCII only and no spaces, and it cannot start with a number)%n", libName);
                return false;
            }

            String[] headers;
            File libProp = new File(libFolder, "library.properties");
            File srcFolder = new File(libFolder, "src");
            if (libProp.exists() && srcFolder.isDirectory()) {
                headers = BaseNoGui.headerListFromIncludePath(srcFolder);
            } else {
                headers = BaseNoGui.headerListFromIncludePath(libFolder);
            }
            if (headers.length == 0) {
                this.logProxy.cliError("Directory/zip file '%s' does not contain a valid library%n", sourceFile.toString());
                return false;
            }

            if (libProp.exists()) {
                try (InputStream propStream = Files.newInputStream(libProp.toPath(), StandardOpenOption.READ)) {
                    Properties properties = new Properties();
                    properties.load(propStream);
                    List<String> dependencies = Arrays.stream(properties.getProperty("depends", "").split(",\\s*"))
                            .filter(TextUtils::anyNotBlank)
                            .collect(Collectors.toList());
                    dependencies.remove("");
                    this.transitiveDependencies.addAll(dependencies);
                } catch (Exception e) {
                    this.logProxy.cliErrorln(e);
                }
            }

            // copy folder
            File destinationFolder = new File(BaseNoGui.getSketchbookLibrariesFolder().folder, sourceFile.getName());
            if (destinationFolder.exists()) {
                this.logProxy.cliInfo("Replacing existing library %s%n", sourceFile.getName());
                FileUtils.recursiveDelete(destinationFolder);
            }
            if (!destinationFolder.mkdir()) {
                this.logProxy.cliError("Cannot create a directory library %s%n", sourceFile.getName());
                return false;
            }
            try {
                FileUtils.copy(sourceFile, destinationFolder);
            } catch (IOException e) {
                this.logProxy.cliErrorln(e);
                return false;
            }

        } catch (IOException e) {
            this.logProxy.cliErrorln(e);
            return false;
        } finally {
            // delete zip created temp folder, if exists
            FileUtils.recursiveDelete(tmpFolder);
        }

        return true;
    }

    public void logSuccessfullyInstalledLib(String libName) {
        this.logProxy.cliInfo("Successfully added library %s!%n", libName);
    }

    public Set<String> getTransitiveDependencies() {
        return transitiveDependencies;
    }

    public void cleanTransitiveDependenciesInfo() {
        this.transitiveDependencies.clear();
    }
}
