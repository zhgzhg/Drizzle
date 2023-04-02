package com.github.zhgzhg.drizzle.utils.file;

import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import com.github.zhgzhg.drizzle.utils.text.TextUtils;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.RefNotFoundException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

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

public class FileUtils<T> {
    private final LogProxy<T> logProxy;

    public static class RepoLibDir {
        public final String url;
        public final String revision;
        public final String resultingLibNameDir;
        public final File dir;

        public RepoLibDir(String url, String revision, String libName, File dir) {
            this.url = url;
            this.revision = revision;
            this.resultingLibNameDir = libName;
            this.dir = dir;
        }
    }

    public FileUtils(LogProxy<T> logProxy) {
        this.logProxy = logProxy;
    }

    public RepoLibDir downloadGit(String url, String desiredLibNameDir, String tempFileNamePostfix) {
        URL realUrl = TextUtils.toURL(url, logProxy::cliErrorln);
        if (realUrl != null) {
            return downloadGit(realUrl, desiredLibNameDir, tempFileNamePostfix);
        }
        return null;
    }

    public File downloadZip(String url, String tempFileNamePostfix) {
        URL realUrl = TextUtils.toURL(url, logProxy::cliErrorln);
        if (realUrl != null) {
            return downloadZip(realUrl, tempFileNamePostfix);
        }
        return null;
    }

    public RepoLibDir downloadGit(URL url, String desiredLibNameDir, String tempDirNamePostfix) {

        File tempDir = null;
        try {
            tempDir = Files.createTempDirectory("ard-drizzle-ext" + (tempDirNamePostfix != null ? tempDirNamePostfix : "")).toFile();
            tempDir.deleteOnExit();

            String revision = (TextUtils.isNotNullOrBlank(url.getRef()) ? url.getRef() : null);
            String libName = desiredLibNameDir;
            String uri = TextUtils.rtrim(url.toExternalForm(), (revision != null ? "#" + revision : null));

            CloneCommand cloneCommand = Git.cloneRepository().setURI(uri).setCloneSubmodules(true).setCloneAllBranches(true);

            File subLibDir = new File(tempDir, desiredLibNameDir);
            if (!subLibDir.mkdir()) {
                throw new IOException("Cannot create temp. directory " + libName);
            }

            cloneCommand.setDirectory(subLibDir);

            if (revision != null) {
                try (Git result = cloneCommand.call();
                     Repository repository = result.getRepository()) {

                    Ref ref = repository.findRef(revision);
                    if (ref != null) {
                        result.checkout().setName(ref.getName()).call();
                    } else {
                        try {
                            result.checkout()
                                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                                    .setName(revision)
                                    .call();
                        } catch (RefNotFoundException fnfe) {
                            result.checkout()
                                    .setUpstreamMode(CreateBranchCommand.SetupUpstreamMode.SET_UPSTREAM)
                                    .setName("origin/" + revision)
                                    .call();
                        }
                    }
                }
            }

            return new RepoLibDir(uri, revision, libName, subLibDir);
        } catch (IOException | GitAPIException e) {
            this.logProxy.cliError("Failed downloading the external git repo library %s - %s%n", url, e.getMessage());
            if (tempDir != null) {
                try {
                    removeDir(tempDir);
                } catch (Exception ex) {
                    // don't care
                }
            }
            return null;
        }
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
                    removeFile(tempFile);
                }
            }).start();
        }
    }

    public void delayedDirRemoval(long millis, File tempDir) {
        if (tempDir != null) {
            new Thread(() -> {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    e.printStackTrace(this.logProxy.stderr());
                    Thread.currentThread().interrupt();
                } finally {
                    removeDir(tempDir);
                }
            }).start();
        }
    }

    private void removeFile(File tempFile) {
        if (tempFile != null) {
            try {
                Files.deleteIfExists(tempFile.toPath());
            } catch (IOException e) {
                this.logProxy.cliErrorln(e);
            }
        }
    }

    private void removeDir(File dir) {
        File[] contents = dir.listFiles();
        if (contents != null) {
            for (File f : contents) {
                removeDir(f);
            }
        }
        dir.delete();
        //removeFile(dir);
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
