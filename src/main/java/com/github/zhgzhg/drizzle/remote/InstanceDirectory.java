package com.github.zhgzhg.drizzle.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The directory where every running Drizzle instance keeps one file describing itself. Each instance writes only
 * its own file, so no locking between processes is needed. Files left behind by crashed IDEs are removed by the
 * clients once they find nothing listening behind them.
 */
public final class InstanceDirectory {
    private static final String FILE_PREFIX = "instance-";
    private static final String FILE_SUFFIX = ".properties";

    private final Path dir;

    public InstanceDirectory(final Path dir) {
        this.dir = dir;
    }

    /**
     * {@code ~/.drizzle/instances}, next to the {@code ~/.drizzle/bin} launcher.
     */
    public static InstanceDirectory defaultLocation() {
        return new InstanceDirectory(Paths.get(System.getProperty("user.home"), ".drizzle", "instances"));
    }

    public Path path() {
        return dir;
    }

    public Path fileOf(final long pid) {
        return dir.resolve(FILE_PREFIX + pid + FILE_SUFFIX);
    }

    /**
     * Atomically (re)writes the file of the instance with {@code info.pid}.
     */
    public Path write(final InstanceInfo info) throws IOException {
        ensureExists();

        Path target = fileOf(info.pid);
        Path tmp = Files.createTempFile(dir, FILE_PREFIX, ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                info.toProperties().store(out, "Drizzle remote control endpoint - see README.md");
            }
            moveIntoPlace(tmp, target);
        } finally {
            Files.deleteIfExists(tmp);
        }
        return target;
    }

    /**
     * @return the instance files that could be read; unreadable or malformed ones are skipped
     */
    public List<InstanceInfo> readAll() {
        List<InstanceInfo> result = new ArrayList<>();
        if (!Files.isDirectory(dir) || !isTrusted()) return result;

        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, FILE_PREFIX + "*" + FILE_SUFFIX)) {
            for (Path file : files) {
                try (InputStream in = Files.newInputStream(file)) {
                    Properties p = new Properties();
                    p.load(in);
                    InstanceInfo info = InstanceInfo.fromProperties(p);
                    info.file = file;
                    result.add(info);
                } catch (IOException | IllegalArgumentException e) {
                    // being rewritten, or not ours - skip it
                }
            }
        } catch (IOException e) {
            // unreadable directory - same as no instances
        }
        return result;
    }

    public void delete(final Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // a later client will retry
        }
    }

    private void ensureExists() throws IOException {
        if (!Files.isDirectory(dir)) {
            // the files hold access tokens, and home directories are often readable by other users on Linux; the
            // files themselves are created owner-only too (Files.createTempFile)
            if (isPosix()) {
                Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else {
                Files.createDirectories(dir);
            }
        }
        if (!isTrusted()) {
            throw new IOException(dir + " belongs to another user; refusing to use it");
        }
    }

    /**
     * On POSIX systems the directory must belong to the current user, otherwise another user could plant fake
     * instance files. On Windows the user's profile folder is private already.
     */
    private boolean isTrusted() {
        if (!isPosix()) return true;
        try {
            return Files.getOwner(dir).getName().equals(System.getProperty("user.name"));
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private static boolean isPosix() {
        return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private static void moveIntoPlace(final Path tmp, final Path target) throws IOException {
        for (int attempt = 1; ; ++attempt) {
            try {
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AccessDeniedException e) {
                // on Windows a client reading the file at this moment blocks the replacement
                if (attempt >= 5) throw e;
                try {
                    Thread.sleep(50L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }
}
