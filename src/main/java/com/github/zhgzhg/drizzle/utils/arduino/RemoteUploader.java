package com.github.zhgzhg.drizzle.utils.arduino;

import cc.arduino.UploaderUtils;
import cc.arduino.packages.BoardPort;
import cc.arduino.packages.Uploader;
import com.github.zhgzhg.drizzle.remote.ActivityGuard;
import com.github.zhgzhg.drizzle.remote.RemoteException;
import com.github.zhgzhg.drizzle.remote.RemoteProtocol;
import com.github.zhgzhg.drizzle.utils.log.LogProxy;
import processing.app.Base;
import processing.app.Editor;
import processing.app.EditorConsole;
import processing.app.EditorStatus;
import processing.app.I18n;
import processing.app.PreferencesData;
import processing.app.SerialNotFoundException;
import processing.app.debug.RunnerException;
import processing.app.helpers.PreferencesMapException;

import javax.swing.*;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Uploads a sketch for a remote caller like Sketch &rarr; Upload (or Upload Using Programmer) does, with the same
 * handling of the Serial Monitor and Plotter, but without the dialogs nobody would answer: the IDE's choice of port
 * when none is selected, and the password of a network board.
 * <p>
 * Runs on the remote job thread, which holds Drizzle's remote activity lease; the build goes through the sketch
 * controller, and thus through the {@code @Preferences} hook, as the IDE's own upload does.
 */
public class RemoteUploader {
    private static final String BUILD_VERBOSE_PREFERENCE = "build.verbose";
    private static final String UPLOAD_VERBOSE_PREFERENCE = "upload.verbose";
    private static final String STAGE_COMPILE = "compile";
    private static final String STAGE_UPLOAD = "upload";

    private final Editor editor;
    private final UILocator uiLocator;
    private final LogProxy<EditorConsole> logProxy;

    public RemoteUploader(final Editor editor, final UILocator uiLocator, final LogProxy<EditorConsole> logProxy) {
        this.editor = editor;
        this.uiLocator = uiLocator;
        this.logProxy = logProxy;
    }

    /**
     * @param port       the address of the port to select first, as Tools &rarr; Port would; null keeps the selected one
     * @param programmer the id of the programmer to select and upload with, like Upload Using Programmer; null for a
     *                   normal upload
     * @param verbose    whether the uploader reports in detail, like the "Show verbose output during upload" preference
     */
    public Map<String, Object> upload(final String port, final String programmer, final boolean verbose) throws Exception {
        EditorInternals internals = EditorInternals.get();
        if (port != null) internals.selectPort(this.editor, port);
        if (programmer != null) selectProgrammer(programmer);

        String portAddress = UploadTargets.selectedPortAddress();
        if (UploadTargets.needsUnknownPassword(new UploaderUtils().getUploaderByPreferences(false))) {
            throw passwordRequired(portAddress);
        }
        // a plain upload goes through the port, unless the board has no bootloader
        boolean throughPort = programmer == null && !UploadTargets.uploadUsesProgrammer();
        boolean portAvailable = UploadTargets.isAvailable(portAddress);

        Optional<EditorStatus> status = this.uiLocator.editorStatus();
        this.uiLocator.editorConsole().ifPresent(EditorConsole::setCurrentEditorConsole);
        this.logProxy.cliInfo("Uploading %s %s, as requested remotely...%n", this.editor.getSketch().getName(),
                programmer != null ? "with the programmer " + programmer : "to " + describePort(portAddress));

        SwingUtilities.invokeLater(() -> {
            this.editor.removeAllLineHighlights();
            status.ifPresent(es -> es.progress("Compiling sketch (remote upload)..."));
        });

        String previousBuildVerbose = PreferencesData.get(BUILD_VERBOSE_PREFERENCE);
        String previousUploadVerbose = PreferencesData.get(UPLOAD_VERBOSE_PREFERENCE);
        PreferencesData.setBoolean(BUILD_VERBOSE_PREFERENCE, false); // the compiler's command lines would flood the log
        PreferencesData.setBoolean(UPLOAD_VERBOSE_PREFERENCE, verbose);

        Map<String, Object> result = new LinkedHashMap<>();
        Runnable reportInUI = () -> { };
        RemoteException failure = null;
        String stage = STAGE_COMPILE;
        Process uploaderBefore = Uploader.programmerPid;

        try {
            // as during the IDE's own upload: the toolbar ignores Verify/Upload, and a Serial Monitor opened now waits
            internals.setUploading(true);
            Editor.avoidMultipleOperations = true;
            internals.suspendMonitors();

            String className = this.editor.getSketchController().build(false, false);
            if (className == null) {
                // the @Preferences hook refused to build; it logged why
                result.put("ok", false);
                result.put("error", error(STAGE_COMPILE, "The build didn't run; the log tells why"));
                reportInUI = () -> this.editor.statusError("The build didn't run; see the console");
            } else if (!ActivityGuard.holdsRemote()) {
                throw new RemoteException(RemoteProtocol.ERR_CANCELLED, "Cancelled before uploading");
            } else {
                stage = STAGE_UPLOAD;
                SwingUtilities.invokeLater(() -> status.ifPresent(es -> es.progressNotice("Uploading (requested remotely)...")));

                String failureMessage = flash(className, programmer != null, portAddress);
                if (failureMessage == null) {
                    result.put("ok", true);
                    reportInUI = () -> this.editor.statusNotice(I18n.tr("Done uploading."));
                } else {
                    reportInUI = () -> this.editor.statusError(failureMessage);
                    if (throughPort && portAddress != null && !portAvailable) {
                        failure = new RemoteException(RemoteProtocol.ERR_PORT_UNAVAILABLE, "Uploading failed: the selected"
                                + " port " + portAddress + " isn't available. " + UploadTargets.availablePortsSentence()
                                + " Choose one with --port.");
                    } else {
                        result.put("ok", false);
                        result.put("error", error(STAGE_UPLOAD, failureMessage));
                    }
                }
            }
        } catch (SerialNotFoundException e) {
            // the upload recipe needs a port, but none is selected
            failure = new RemoteException(RemoteProtocol.ERR_PORT_UNAVAILABLE, "No port is selected. "
                    + UploadTargets.availablePortsSentence() + " Choose one with --port.");
            reportInUI = () -> this.editor.statusError(I18n.tr("Serial port not selected."));
        } catch (RunnerException e) {
            // a compilation error, or e.g. no board answering on the port
            Map<String, Object> error = error(stage, messageOf(e));
            if (STAGE_COMPILE.equals(stage)) error.putAll(describeCompileError(e));
            result.put("ok", false);
            result.put("error", error);
            reportInUI = () -> this.editor.statusError(e);
        } catch (PreferencesMapException e) {
            String message = I18n.format(I18n.tr("Error while uploading: missing '{0}' configuration parameter"), e.getMessage());
            result.put("ok", false);
            result.put("error", error(stage, message));
            reportInUI = () -> this.editor.statusError(message);
        } catch (IOException e) {
            result.put("ok", false);
            result.put("error", error(stage, messageOf(e)));
            reportInUI = () -> this.editor.statusError(e);
        } finally {
            if (!ActivityGuard.holdsRemote()) {
                // cancelled: the IDE's uploader ignores the interrupt and would keep the port busy
                stopUploaderStartedAfter(uploaderBefore);
            }
            restore(BUILD_VERBOSE_PREFERENCE, previousBuildVerbose);
            restore(UPLOAD_VERBOSE_PREFERENCE, previousUploadVerbose);

            internals.setUploading(false);
            Editor.avoidMultipleOperations = false;

            Runnable report = reportInUI;
            SwingUtilities.invokeLater(() -> {
                status.ifPresent(EditorStatus::unprogress);
                report.run();
            });

            internals.resumeMonitors(this.editor); // may wait for the port to come back after a board reset
            SwingUtilities.invokeLater(() -> {
                if (Base.INSTANCE != null) Base.INSTANCE.onBoardOrPortChange();
            });
        }

        if (failure != null) throw failure;
        result.put("port", UploadTargets.describeSelectedPort());
        if (programmer != null) result.put("programmer", programmer);
        return result;
    }

    /**
     * Like the IDE's upload after the build, minus the password dialog and its retry loop.
     *
     * @return null when the upload succeeded, otherwise why it failed
     */
    private String flash(final String className, final boolean usingProgrammer, final String portAddress) throws Exception {
        UploaderUtils uploaderUtils = new UploaderUtils();
        Uploader uploader = uploaderUtils.getUploaderByPreferences(false);
        if (UploadTargets.needsUnknownPassword(uploader)) {
            throw passwordRequired(portAddress);
        }

        this.uiLocator.editorConsole().ifPresent(EditorConsole::setCurrentEditorConsole);
        List<String> warnings = new LinkedList<>();
        boolean success = uploaderUtils.upload(this.editor.getSketch(), uploader, className, usingProgrammer, false, warnings);
        for (String warning : warnings) {
            System.out.println(I18n.tr("Warning") + ": " + warning);
        }

        if (success) return null;
        String message = uploader.getFailureMessage();
        return message == null || message.isEmpty() ? I18n.tr("An error occurred while uploading the sketch") : message;
    }

    private static void selectProgrammer(final String id) throws Exception {
        List<UploadTargets.Programmer> programmers = UploadTargets.programmers();
        if (programmers.stream().noneMatch(p -> p.id.equals(id))) {
            throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "The selected board has no programmer " + id
                    + ". " + (programmers.isEmpty() ? "It has none." : "Its programmers: "
                    + programmers.stream().map(p -> p.id + " (" + p.name + ")").collect(Collectors.joining(", ")) + "."));
        }
        if (id.equals(UploadTargets.selectedProgrammerId())) return;

        // what a click in Tools > Programmer does
        SwingUtilities.invokeAndWait(() -> {
            PreferencesData.set(UploadTargets.PROGRAMMER_PREFERENCE, id);
            if (Base.INSTANCE == null) return;
            for (JMenuItem item : Base.INSTANCE.getProgrammerMenus()) {
                Action action = item.getAction();
                if (action != null && id.equals(action.getValue("id"))) item.setSelected(true);
            }
        });
    }

    private static RemoteException passwordRequired(final String portAddress) {
        return new RemoteException(RemoteProtocol.ERR_PASSWORD_REQUIRED, "The board at " + portAddress + " needs a password"
                + " to upload, which Arduino IDE asks for in a dialog. After one upload from the IDE (Sketch > Upload)"
                + " it remembers the password until it exits.");
    }

    private static String describePort(final String address) {
        if (address == null) return "the board (no port is selected)";
        BoardPort port = UploadTargets.findPort(address);
        return port != null ? UploadTargets.label(port) : address + " (not available)";
    }

    /**
     * Only an uploader started by this upload: after the cancel, the user may start the next one.
     */
    private static void stopUploaderStartedAfter(final Process before) {
        Process uploader = Uploader.programmerPid;
        if (uploader != null && uploader != before && uploader.isAlive()) {
            uploader.destroyForcibly();
        }
    }

    /**
     * @return the error's message, and where it is when it's about a sketch file
     */
    public static Map<String, Object> describeCompileError(final RunnerException e) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", messageOf(e));
        if (e.hasCodeFile()) {
            // without a file the line is meaningless: RunnerException(Exception) leaves it at 0
            error.put("file", e.getCodeFile().getFile().getAbsolutePath());
            if (e.hasCodeLine()) error.put("line", e.getCodeLine() + 1); // RunnerException counts lines from 0
            if (e.hasCodeLine() && e.getCodeColumn() > 0) error.put("column", e.getCodeColumn());
        }
        return error;
    }

    private static String messageOf(final Exception e) {
        return e.getMessage() != null ? e.getMessage() : String.valueOf(e.getCause() != null ? e.getCause() : e);
    }

    private static Map<String, Object> error(final String stage, final String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("stage", stage);
        error.put("message", message);
        return error;
    }

    private static void restore(final String preference, final String previousValue) {
        if (previousValue == null) {
            PreferencesData.remove(preference);
        } else {
            PreferencesData.set(preference, previousValue);
        }
    }
}
