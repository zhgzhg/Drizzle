package com.github.zhgzhg.drizzle.remote;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Stands in for the IDE. Its behaviour depends on the sketch's folder name: "broken" fails to compile, "unsaved" has
 * unsaved changes, "slow" waits for {@link #release} (or an interrupt), "stuck" waits for {@link #release} ignoring
 * interrupts, like a stalled download. Uploading to port COM99 fails because it isn't available.
 */
class FakeCommandHandler implements RemoteCommandHandler {
    final List<File> openSketchFolders = new ArrayList<>();
    final CountDownLatch release = new CountDownLatch(1);
    final CountDownLatch started = new CountDownLatch(1);
    volatile Map<String, String> lastArgs;
    volatile boolean uiBlocked;

    FakeCommandHandler(final File... openSketchFolders) {
        this.openSketchFolders.addAll(Arrays.asList(openSketchFolders));
    }

    @Override
    public Map<String, Object> status() throws RemoteException {
        if (uiBlocked) {
            throw new RemoteException(RemoteProtocol.ERR_IDE_UNRESPONSIVE, "a dialog may be waiting for an answer");
        }

        List<Map<String, Object>> sketches = new ArrayList<>();
        for (File folder : openSketchFolders) {
            Map<String, Object> sketch = new LinkedHashMap<>();
            sketch.put("name", folder.getName());
            sketch.put("path", new File(folder, folder.getName() + ".ino").getPath());
            sketch.put("folder", folder.getPath());
            sketch.put("modified", false);
            sketches.add(sketch);
        }

        Map<String, Object> status = new LinkedHashMap<>();
        status.put("drizzle", "test");
        status.put("ide", "1.8.19");
        status.put("sketches", sketches);
        return status;
    }

    @Override
    public boolean supports(final String command) {
        return Arrays.asList(RemoteProtocol.CMD_OPEN, RemoteProtocol.CMD_COMPILE, RemoteProtocol.CMD_APPLY_MARKERS,
                RemoteProtocol.CMD_UPLOAD, RemoteProtocol.CMD_LIST_FQBNS, RemoteProtocol.CMD_LIST_PORTS,
                RemoteProtocol.CMD_LIST_PROGRAMMERS).contains(command);
    }

    @Override
    public boolean isExclusive(final String command) {
        return !Arrays.asList(RemoteProtocol.CMD_OPEN, RemoteProtocol.CMD_LIST_FQBNS, RemoteProtocol.CMD_LIST_PORTS,
                RemoteProtocol.CMD_LIST_PROGRAMMERS).contains(command);
    }

    @Override
    public Map<String, Object> execute(final String command, final Map<String, String> args, final Consumer<String> log)
            throws RemoteException {
        lastArgs = args;
        Map<String, Object> result = new LinkedHashMap<>();
        String sketch = args.getOrDefault(RemoteProtocol.ARG_SKETCH, "");

        if (RemoteProtocol.CMD_OPEN.equals(command)) {
            File folder = new File(sketch).isDirectory() ? new File(sketch) : new File(sketch).getParentFile();
            openSketchFolders.add(folder);
            result.put("ok", true);
            return result;
        }

        if (sketch.contains("unsaved")) {
            throw new RemoteException(RemoteProtocol.ERR_UNSAVED_CHANGES, "The sketch has unsaved changes");
        }
        if (RemoteProtocol.CMD_UPLOAD.equals(command) && "COM99".equals(args.get(RemoteProtocol.ARG_PORT))) {
            throw new RemoteException(RemoteProtocol.ERR_PORT_UNAVAILABLE, "The port COM99 isn't available");
        }

        log.accept(command + " started\n");
        started.countDown();
        if (sketch.contains("slow")) {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else if (sketch.contains("stuck")) {
            boolean released = false;
            while (!released) {
                try {
                    released = release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // like a blocking download, it doesn't notice interrupts
                }
            }
        }
        log.accept(command + " finished\n");

        boolean broken = sketch.contains("broken");
        result.put("ok", !broken);
        if (broken) {
            result.put("diagnostics", CompilerDiagnostics.parse(sketch + ":3:5: error: 'x' was not declared in this scope"));
        }
        return result;
    }
}
