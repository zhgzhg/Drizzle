package com.github.zhgzhg.drizzle.utils.file;

import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FileUtils {
    private final LogProxy logProxy;

    public FileUtils(LogProxy logProxy) {
        this.logProxy = logProxy;
    }

    public File downloadZip(String url, String tempFileNamePostfix) {
        URL realUrl = TextUtils.toURL(url, logProxy::cliErrorln);
        if (realUrl != null) {
            return downloadZip(realUrl, tempFileNamePostfix);
        }
        return null;
    }

    public File downloadZip(URL url, String tempFileNamePostfix) {

        File tempFile;
        try {
            tempFile = Files.createTempFile("ard-drizzle-ext" + (tempFileNamePostfix != null ? tempFileNamePostfix : ""), ".zip")
                    .toFile();
            tempFile.deleteOnExit();
        } catch (IOException e) {
            this.logProxy.cliError("Failed creating temporary file for downloading the external library %s%n", url);
            return null;
        }

        try (ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
                FileChannel fileOutputChannel = new FileOutputStream(tempFile).getChannel()) {

            long bytesTransferred = fileOutputChannel.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
            if (bytesTransferred < 1) {
                throw new IOException("File of 0 bytes size");
            }

        } catch (IOException e) {
            this.logProxy.cliError("Failed transferring %s:%n", url);
            this.logProxy.cliErrorln(e);
            return null;
        }

        return tempFile;
    }

    public void delayedFileRemoval(long millis, File tempFile) {
        if (tempFile != null) {
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
    }

    public List<Path> listJarsInDir(Path inPath) {
        try (Stream<Path> walker = Files.walk(inPath, 1)) {
            return walker.filter(path -> Files.isRegularFile(path) && path.toString().toLowerCase().endsWith(".jar"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (NoSuchFileException e) {
            // the actual path doesn't exist
        } catch (IOException e) {
            e.printStackTrace(this.logProxy.stderr());
        }

        return Collections.emptyList();
    }

    public Path caseInsensitiveShallowDirSearch(Path basePath, String dirName) {
        String dirNameInLowerCase = dirName.toLowerCase();
        List<Path> candidates;
        try (Stream<Path> walker = Files.walk(basePath, 1)) {
            candidates = walker.filter(p -> Files.isDirectory(p) && p.getFileName().toString().equalsIgnoreCase(dirNameInLowerCase))
                    .collect(Collectors.toList());
        } catch (IOException ex) {
            candidates = Collections.emptyList();
        }

        Path selected = null;
        for (Path candidate : candidates) {
            selected = candidate;
            if (dirName.equals(candidate.getFileName().toString())) {
                break;
            }
        }

        return selected;
    }

    public boolean removeDir(Path dir) {
        try (Stream<Path> walker = Files.walk(dir)) {
            walker.sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            return Files.exists(dir);
        } catch (FileNotFoundException ex) {
            // we don't care
            return true;
        } catch (IOException ex) {
            this.logProxy.cliErrorln(ex);
            return false;
        }
    }
}
