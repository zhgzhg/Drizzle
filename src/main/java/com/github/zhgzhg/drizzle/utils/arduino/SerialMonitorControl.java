package com.github.zhgzhg.drizzle.utils.arduino;

import cc.arduino.packages.BoardPort;
import com.github.zhgzhg.drizzle.remote.RemoteException;
import com.github.zhgzhg.drizzle.remote.RemoteProtocol;
import processing.app.AbstractMonitor;
import processing.app.AbstractTextMonitor;
import processing.app.Base;
import processing.app.Editor;
import processing.app.EditorConsole;
import processing.app.EditorStatus;
import processing.app.PreferencesData;

import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import static com.github.zhgzhg.drizzle.remote.IDERemoteCommandHandler.onUiThread;

/**
 * Opens and closes the IDE's Serial Monitor and sends text to the board for remote callers, the way the user would:
 * through Tools &rarr; Serial Monitor, the window's baud rate list, its close button and its input field. What it does
 * shows in the IDE's console and status bar. Runs on the remote job thread.
 */
public final class SerialMonitorControl {
    private static final List<Integer> BAUD_RATES = Arrays.asList(300, 1200, 2400, 4800, 9600, 19200, 38400, 57600,
            74880, 115200, 230400, 250000, 500000, 1000000, 2000000);
    private static final String BAUD_RATE_PREFERENCE = "serial.debug_rate";
    private static final String LINE_ENDING_PREFERENCE = "serial.line_ending";
    // in the order of the monitor's line ending list, which is what the preference holds
    private static final List<String> LINE_ENDING_NAMES = Arrays.asList("none", "nl", "cr", "crlf");
    private static final String[] LINE_ENDINGS = { "", "\n", "\r", "\r\n" };
    private static final long UI_TIMEOUT_SECONDS = 10;
    private static final long OPEN_TIMEOUT_SECONDS = 30;

    private final SerialMonitorTap tap;

    public SerialMonitorControl(final SerialMonitorTap tap) {
        this.tap = tap;
    }

    /**
     * Opens the Serial Monitor like Tools &rarr; Serial Monitor, after selecting the port and baud rate if asked to.
     * Both choices stay, as if made in the IDE.
     */
    public Map<String, Object> open(final String portAddress, final String baudRate) throws Exception {
        Integer baud = baudRate != null ? parseBaudRate(baudRate) : null;
        EditorInternals internals = tap.internals();
        Editor editor = editor();

        String address = portAddress != null ? portAddress : UploadTargets.selectedPortAddress();
        if (address == null) {
            throw new RemoteException(RemoteProtocol.ERR_PORT_UNAVAILABLE, "No port is selected. "
                    + UploadTargets.availablePortsSentence() + " Choose one with --port.");
        }
        BoardPort port = UploadTargets.findPort(address);
        if (port == null) {
            throw new RemoteException(RemoteProtocol.ERR_PORT_UNAVAILABLE, (portAddress != null ? "The port " : "The selected port ")
                    + address + " isn't available. " + UploadTargets.availablePortsSentence()
                    + (portAddress == null ? " Choose one with --port." : ""));
        }
        if (!"serial".equals(port.getProtocol())) {
            throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "Drizzle follows the Serial Monitor of serial ports only;"
                    + " " + port.getAddress() + " is a " + port.getProtocol() + " port");
        }

        long next = tap.log().endPosition();
        Map<String, Object> before = ui(() -> {
            tap.poll();
            return tap.describe();
        });

        // the plotter holds the port, and the IDE doesn't show it together with the Serial Monitor
        if (before != null && SerialMonitorTap.SERIAL_PLOTTER.equals(before.get("window"))) {
            if (port.getAddress().equals(before.get("port")) && (baud == null || baud.equals(before.get("baud")))) {
                return result(true, next);
            }
            throw new RemoteException(RemoteProtocol.ERR_FAILED, "The Serial Plotter is open on " + before.get("port")
                    + " at " + before.get("baud") + " baud, and the IDE doesn't show the Serial Monitor meanwhile."
                    + " Ask the user to close the plotter, or read its input with serial-log.");
        }
        boolean wasOpen = before != null;

        if (portAddress != null) internals.selectPort(editor, port.getAddress()); // a shown monitor follows
        if (baud != null) {
            ui(() -> {
                selectBaudRate(internals, baud); // a shown monitor reopens its port at that rate
                return null;
            });
        }
        if (!wasOpen) {
            ui(() -> {
                editor.handleSerial();
                return null;
            }, OPEN_TIMEOUT_SECONDS);
        }

        Map<String, Object> after = ui(() -> {
            tap.poll();
            return tap.describe();
        });
        if (after == null || !SerialMonitorTap.SERIAL_MONITOR.equals(after.get("window"))) {
            String reason = ui(() -> statusMessage(editor));
            throw new RemoteException(RemoteProtocol.ERR_FAILED, "The IDE didn't open the Serial Monitor"
                    + (reason != null ? ": " + reason : ""));
        }

        if (!wasOpen) {
            announce(editor, "Opened the Serial Monitor on " + after.get("port") + " at " + after.get("baud")
                    + " baud, as requested remotely.");
        } else if (!Objects.equals(after.get("port"), before.get("port")) || !Objects.equals(after.get("baud"), before.get("baud"))) {
            announce(editor, "Switched the Serial Monitor to " + after.get("port") + " at " + after.get("baud")
                    + " baud, as requested remotely.");
        }
        return result(wasOpen, next);
    }

    /**
     * Closes the Serial Monitor, or the Serial Plotter, like its window's close button.
     */
    public Map<String, Object> close() throws Exception {
        Editor editor = editor();
        String closed = ui(() -> {
            AbstractMonitor window = tap.shownWindow();
            if (window == null) return null;

            String name = SerialMonitorTap.nameOf(window);
            tap.poll(); // changes the user made come before the close in the record
            tap.closingRemotely(true);
            try {
                window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
                tap.poll();
            } finally {
                tap.closingRemotely(false);
            }
            if (window.isVisible()) {
                throw new RemoteException(RemoteProtocol.ERR_FAILED, "The IDE didn't close the " + name);
            }
            return name;
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        if (closed == null) {
            result.put("alreadyClosed", true);
        } else {
            result.put("closed", closed);
            announce(editor, "Closed the " + closed + ", as requested remotely.");
        }
        return result;
    }

    /**
     * Sends text to the board like the input field of the Serial Monitor (or Plotter) does.
     *
     * @param lineEnding none, nl, cr or crlf; null for the one chosen in the monitor
     */
    public Map<String, Object> send(final String text, final String lineEnding) throws Exception {
        String ending;
        if (lineEnding != null) {
            int index = LINE_ENDING_NAMES.indexOf(lineEnding);
            if (index < 0) {
                throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "The line ending must be one of "
                        + String.join(", ", LINE_ENDING_NAMES));
            }
            ending = LINE_ENDINGS[index];
        } else {
            int index = PreferencesData.getInteger(LINE_ENDING_PREFERENCE, 1);
            ending = index >= 0 && index < LINE_ENDINGS.length ? LINE_ENDINGS[index] : "\n";
        }
        String data = (text != null ? text : "") + ending;
        if (data.isEmpty()) {
            throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "Nothing to send: the text is empty, and so is the"
                    + " line ending");
        }

        Editor editor = editor();
        String port = ui(() -> {
            AbstractMonitor window = tap.shownWindow();
            if (window == null) {
                throw new RemoteException(RemoteProtocol.ERR_MONITOR_CLOSED, "The Serial Monitor isn't open. Open it with"
                        + " serial-open; if the user closed it, ask them first.");
            }
            String name = SerialMonitorTap.nameOf(window);
            Object serial = window.isClosed() ? null : serialOf(window);
            if (serial == null) {
                throw new RemoteException(RemoteProtocol.ERR_MONITOR_CLOSED, "The " + name + " is paused, e.g. during an"
                        + " upload or while the board is unplugged; try again once it has resumed.");
            }
            tap.poll(); // changes the user made come before the sent text in the record
            // processing.app.Serial.write(String), like the monitor's Send button; a failure only gets printed
            serial.getClass().getMethod("write", String.class).invoke(serial, data);
            tap.log().sent(data);
            return SerialMonitorTap.addressOf(window);
        });
        announce(editor, "Sent to " + port + ", as requested remotely: " + printable(data));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("sent", data);
        result.put("monitor", tap.describe());
        result.put("next", tap.log().endPosition());
        return result;
    }

    private Map<String, Object> result(final boolean alreadyOpen, final long next) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("alreadyOpen", alreadyOpen);
        result.put("monitor", tap.describe());
        result.put("next", next); // as --from: what arrives from the opening on
        return result;
    }

    private static Integer parseBaudRate(final String baudRate) throws RemoteException {
        try {
            int baud = Integer.parseInt(baudRate.trim());
            if (BAUD_RATES.contains(baud)) return baud;
        } catch (NumberFormatException e) {
            // reported below
        }
        throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "The Serial Monitor offers these baud rates: "
                + BAUD_RATES.stream().map(String::valueOf).collect(Collectors.joining(", ")));
    }

    /**
     * Like choosing the rate in the monitor's list. Call on the event dispatch thread.
     */
    @SuppressWarnings("unchecked")
    private static void selectBaudRate(final EditorInternals internals, final int baud) throws ReflectiveOperationException {
        PreferencesData.setInteger(BAUD_RATE_PREFERENCE, baud); // read by the next monitor that opens

        AbstractMonitor monitor = internals.serialMonitor();
        if (!(monitor instanceof AbstractTextMonitor) || !monitor.isVisible()) return;

        Field ratesField = EditorInternals.accessible(AbstractTextMonitor.class.getDeclaredField("serialRates"));
        JComboBox<String> rates = (JComboBox<String>) ratesField.get(monitor);
        for (int i = 0; i < rates.getItemCount(); ++i) {
            String item = rates.getItemAt(i);
            if (item != null && item.startsWith(baud + " ")) {
                if (rates.getSelectedIndex() != i) rates.setSelectedIndex(i); // the monitor's own handler reopens the port
                return;
            }
        }
    }

    /**
     * @return the monitor's processing.app.Serial (whose class refers to a library Drizzle doesn't compile against),
     * or null while its port is closed
     */
    private static Object serialOf(final AbstractMonitor window) throws ReflectiveOperationException, RemoteException {
        Field field;
        try {
            field = window.getClass().getDeclaredField("serial");
        } catch (NoSuchFieldException e) {
            throw new RemoteException(RemoteProtocol.ERR_FAILED, "Drizzle can't send through the monitor of a network board");
        }
        return EditorInternals.accessible(field).get(window);
    }

    private static Editor editor() throws RemoteException {
        Editor editor = ui(() -> {
            Base base = Base.INSTANCE;
            if (base == null) return null;
            if (base.getActiveEditor() != null) return base.getActiveEditor();
            return base.getEditors().isEmpty() ? null : base.getEditors().get(0);
        });
        if (editor == null) {
            throw new RemoteException(RemoteProtocol.ERR_FAILED, "Arduino IDE has no window open");
        }
        return editor;
    }

    /**
     * @return what the editor's status bar shows, or null
     */
    private static String statusMessage(final Editor editor) {
        EditorStatus status = new UILocator(editor).editorStatus().orElse(null);
        if (status == null) return null;
        try {
            Object message = EditorInternals.accessible(EditorStatus.class.getDeclaredField("message")).get(status);
            return message != null && !message.toString().trim().isEmpty() ? message.toString().trim() : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Tells the user, in the IDE's console and status bar.
     */
    private static void announce(final Editor editor, final String message) {
        new UILocator(editor).editorConsole().ifPresent(EditorConsole::setCurrentEditorConsole);
        System.out.println(message);
        SwingUtilities.invokeLater(() -> editor.statusNotice(message));
    }

    private static String printable(final String data) {
        return data.replace("\r", "\\r").replace("\n", "\\n");
    }

    private static <T> T ui(final Callable<T> task) throws RemoteException {
        return onUiThread(task, UI_TIMEOUT_SECONDS);
    }

    private static <T> T ui(final Callable<T> task, final long timeoutSeconds) throws RemoteException {
        return onUiThread(task, timeoutSeconds);
    }
}
