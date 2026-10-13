package com.github.zhgzhg.drizzle.remote;

import com.github.zhgzhg.drizzle.Drizzle;
import com.github.zhgzhg.drizzle.DrizzleCLI;
import com.github.zhgzhg.drizzle.utils.arduino.UpdateUtils;
import processing.app.Base;
import processing.app.BaseNoGui;
import processing.app.Editor;
import processing.app.Sketch;
import processing.app.SketchFile;
import processing.app.debug.TargetBoard;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Carries out remote commands inside Arduino IDE. Commands about a sketch are forwarded to the Drizzle instance of
 * the editor window showing it: every window has its own instance (in its own class loader), which registers a
 * request endpoint as the {@link #ENDPOINT_PROPERTY} client property of the window's root pane. Only JDK types
 * cross that boundary.
 */
public final class IDERemoteCommandHandler implements RemoteCommandHandler {

    /**
     * A {@code Function<Map<String, Object>, Map<String, Object>>} taking {@code {"command": ..., "args": {...}}}.
     * It returns the command's result, or {@code {"failure": {"code": ..., "message": ...}}}.
     */
    public static final String ENDPOINT_PROPERTY = "drizzle.remote.endpoint";

    private static final List<String> COMMANDS = Arrays.asList(RemoteProtocol.CMD_OPEN, RemoteProtocol.CMD_APPLY_MARKERS,
            RemoteProtocol.CMD_COMPILE, RemoteProtocol.CMD_AUTOGEN_MARKERS, RemoteProtocol.CMD_LIST_FQBNS);

    private static final long UI_TIMEOUT_SECONDS = 10; // shorter than the client's read timeout
    private static final long OPEN_TIMEOUT_SECONDS = 60;
    private static final long ENDPOINT_WAIT_MS = 15000;

    @Override
    public Map<String, Object> status() throws RemoteException {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("drizzle", DrizzleCLI.version());
        status.put("ide", UpdateUtils.arduinoVersion());
        status.put("board", selectedBoard());
        status.put("sketches", onUiThread(() -> {
            List<Map<String, Object>> sketches = new ArrayList<>();
            for (Editor editor : editors()) {
                sketches.add(describe(editor));
            }
            return sketches;
        }, UI_TIMEOUT_SECONDS));
        return status;
    }

    /**
     * @return the main files of the open sketches
     */
    public List<String> openSketchPaths() throws RemoteException {
        return onUiThread(() -> {
            List<String> paths = new ArrayList<>();
            for (Editor editor : editors()) {
                paths.add(canonical(editor.getSketch().getPrimaryFile().getFile()).getPath());
            }
            return paths;
        }, UI_TIMEOUT_SECONDS);
    }

    @Override
    public boolean supports(final String command) {
        return COMMANDS.contains(command);
    }

    /**
     * Opening a sketch and listing boards are quick and change nothing the IDE's own actions depend on.
     */
    @Override
    public boolean isExclusive(final String command) {
        return !RemoteProtocol.CMD_OPEN.equals(command) && !RemoteProtocol.CMD_LIST_FQBNS.equals(command);
    }

    @Override
    public Map<String, Object> execute(final String command, final Map<String, String> args, final Consumer<String> log)
            throws RemoteException {

        StringBuilder output = new StringBuilder();
        try (OutputCapture capture = OutputCapture.start(text -> {
            log.accept(text);
            synchronized (output) {
                output.append(text);
            }
        })) {
            if (RemoteProtocol.CMD_OPEN.equals(command)) {
                return open(requireSketch(args));
            }
            if (RemoteProtocol.CMD_LIST_FQBNS.equals(command)) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("ok", true);
                result.put("fqbns", Drizzle.availableFqbns());
                return result;
            }

            Editor editor = findEditor(requireSketch(args));
            Map<String, Object> result = forwardToEditor(editor, command, args);

            if (RemoteProtocol.CMD_COMPILE.equals(command)) {
                List<Map<String, Object>> diagnostics;
                synchronized (output) {
                    diagnostics = CompilerDiagnostics.parse(output.toString());
                }
                resolveSketchFileNames(editor, diagnostics);
                result.put("diagnostics", diagnostics);
            } else {
                result.put("errorOutput", capture.errorLines());
            }
            result.put("board", selectedBoard());
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> forwardToEditor(final Editor editor, final String command, final Map<String, String> args)
            throws RemoteException {

        Object endpoint = onUiThread(() -> editor.getRootPane().getClientProperty(ENDPOINT_PROPERTY), UI_TIMEOUT_SECONDS);
        if (!(endpoint instanceof Function)) {
            throw new RemoteException(RemoteProtocol.ERR_FAILED,
                    "Drizzle isn't active in the window of " + editor.getSketch().getName() + " (yet); retry in a moment");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("command", command);
        request.put("args", new LinkedHashMap<>(args));

        Map<String, Object> response = ((Function<Map<String, Object>, Map<String, Object>>) endpoint).apply(request);
        Object failure = response.get("failure");
        if (failure instanceof Map) {
            Map<?, ?> f = (Map<?, ?>) failure;
            throw new RemoteException(String.valueOf(f.get("code")), String.valueOf(f.get("message")));
        }
        return new LinkedHashMap<>(response);
    }

    private Map<String, Object> open(final String sketchPath) throws RemoteException {
        File given = new File(sketchPath);
        File folder = given.isDirectory() ? given : given.getParentFile();
        File ino = new File(folder, folder.getName() + ".ino");

        // Arduino IDE shows a dialog when the main file doesn't follow its naming rules; avoid that
        if (!ino.isFile()) {
            throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "Arduino IDE opens sketches whose main file is "
                    + "<folder>/<folder name>.ino; expected " + ino.getPath());
        }

        Editor editor = onUiThread(() -> {
            Base base = Base.INSTANCE;
            if (base == null) {
                throw new RemoteException(RemoteProtocol.ERR_IDE_UNRESPONSIVE, "Arduino IDE is still starting; retry in a moment");
            }
            return base.handleOpen(ino);
        }, OPEN_TIMEOUT_SECONDS);
        if (editor == null) {
            throw new RemoteException(RemoteProtocol.ERR_FAILED, "Arduino IDE didn't open " + ino.getPath());
        }

        // the new window's Drizzle registers its endpoint shortly after the window shows up; its UI work may be slow
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ENDPOINT_WAIT_MS);
        while (!hasEndpoint(editor) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        Map<String, Object> sketch;
        try {
            sketch = onUiThread(() -> describe(editor), UI_TIMEOUT_SECONDS);
        } catch (RemoteException e) {
            sketch = new LinkedHashMap<>();
            sketch.put("name", folder.getName());
            sketch.put("path", canonical(ino).getPath());
            sketch.put("folder", canonical(folder).getPath());
            sketch.put("drizzleReady", false);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("sketch", sketch);
        return result;
    }

    private static boolean hasEndpoint(final Editor editor) {
        try {
            return onUiThread(() -> editor.getRootPane().getClientProperty(ENDPOINT_PROPERTY) != null, 2);
        } catch (RemoteException e) {
            return false; // the UI is busy; keep waiting
        }
    }

    /**
     * Arduino IDE prints errors in sketch files with the tab's name (e.g. "blink:12:3: error: ...") instead of a path.
     */
    private static void resolveSketchFileNames(final Editor editor, final List<Map<String, Object>> diagnostics)
            throws RemoteException {

        Map<String, String> pathsByName = onUiThread(() -> {
            Map<String, String> paths = new HashMap<>();
            for (SketchFile file : editor.getSketch().getFiles()) {
                String path = canonical(file.getFile()).getPath();
                paths.put(file.getPrettyName(), path);
                paths.put(file.getFileName(), path);
            }
            return paths;
        }, UI_TIMEOUT_SECONDS);

        for (Map<String, Object> diagnostic : diagnostics) {
            String path = pathsByName.get(String.valueOf(diagnostic.get("file")));
            if (path != null) diagnostic.put("file", path);
        }
    }

    private Editor findEditor(final String sketchPath) throws RemoteException {
        File given = canonical(new File(sketchPath));
        File folder = given.isDirectory() ? given : given.getParentFile();

        Editor editor = onUiThread(() -> {
            for (Editor e : editors()) {
                if (RemoteClient.samePath(canonical(e.getSketch().getFolder()).getPath(), folder.getPath())) {
                    return e;
                }
            }
            return null;
        }, UI_TIMEOUT_SECONDS);

        if (editor == null) {
            throw new RemoteException(RemoteProtocol.ERR_SKETCH_NOT_OPEN, "The sketch " + folder + " isn't open in this IDE");
        }
        return editor;
    }

    /**
     * Base.INSTANCE is assigned only after the IDE restored its windows, and Drizzle starts in the first of them.
     * Call on the event dispatch thread.
     */
    private static List<Editor> editors() {
        Base base = Base.INSTANCE;
        return base == null ? Collections.emptyList() : base.getEditors();
    }

    private static Map<String, Object> describe(final Editor editor) {
        Sketch sketch = editor.getSketch();
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("name", sketch.getName());
        description.put("path", canonical(sketch.getPrimaryFile().getFile()).getPath());
        description.put("folder", canonical(sketch.getFolder()).getPath());
        description.put("modified", sketch.isModified());
        description.put("drizzleReady", editor.getRootPane().getClientProperty(ENDPOINT_PROPERTY) instanceof Function);
        return description;
    }

    private static Map<String, Object> selectedBoard() {
        TargetBoard board = BaseNoGui.getTargetBoard();
        if (board == null) return null;

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("fqbn", board.getFQBN());
        description.put("name", board.getName());
        return description;
    }

    private static String requireSketch(final Map<String, String> args) throws RemoteException {
        String sketch = args.get(RemoteProtocol.ARG_SKETCH);
        if (sketch == null || sketch.trim().isEmpty()) {
            throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "Missing the \"sketch\" argument");
        }
        return sketch;
    }

    private static File canonical(final File file) {
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            return file.getAbsoluteFile();
        }
    }

    /**
     * Runs a task on Swing's event dispatch thread and waits for it, but not forever: a modal dialog or a long
     * operation can keep the thread busy.
     */
    static <T> T onUiThread(final Callable<T> task, final long timeoutSeconds) throws RemoteException {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                return task.call();
            }

            FutureTask<T> future = new FutureTask<>(task);
            SwingUtilities.invokeLater(future);
            try {
                return future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(false);
                throw new RemoteException(RemoteProtocol.ERR_IDE_UNRESPONSIVE, "Arduino IDE's window didn't respond within "
                        + timeoutSeconds + " seconds; a dialog may be waiting for an answer");
            } catch (ExecutionException e) {
                throw e.getCause() instanceof Exception ? (Exception) e.getCause() : new RuntimeException(e.getCause());
            }
        } catch (RemoteException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteException(RemoteProtocol.ERR_INTERNAL, "Interrupted");
        } catch (Exception e) {
            throw new RemoteException(RemoteProtocol.ERR_INTERNAL, e.toString(), e);
        }
    }
}
