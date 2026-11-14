package com.github.zhgzhg.drizzle.utils.arduino;

import com.github.zhgzhg.drizzle.remote.SerialLog;
import processing.app.AbstractMonitor;
import processing.app.AbstractTextMonitor;
import processing.app.Editor;
import processing.app.SerialMonitor;
import processing.app.SerialPlotter;

import javax.swing.JButton;
import javax.swing.Timer;
import java.awt.AWTEvent;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Records in a {@link SerialLog} what the IDE's Serial Monitor or Serial Plotter receives, and what happens to them:
 * opened, paused for an upload, resumed, closed, cleared, switched to another baud rate; also the IDE's uploads.
 * The windows keep showing what they always do.
 * <p>
 * A monitor hands what it received to its window about 30 times a second, when its update timer fires. Drizzle takes
 * the monitor's place as that timer's listener, records the text, and passes it on to the monitor. Monitors of
 * network boards hand their text over differently and don't get recorded.
 * <p>
 * Everything here runs on the event dispatch thread, except {@link #describe()}.
 */
public final class SerialMonitorTap {
    public static final String SERIAL_MONITOR = "Serial Monitor";
    public static final String SERIAL_PLOTTER = "Serial Plotter";
    private static final int POLL_MS = 250;
    private static final String OPEN = "open";
    private static final String PAUSED = "paused";

    private final SerialLog log;
    private final EditorInternals internals;
    private final Field updateTimer;
    private final Method consumeUpdateBuffer;
    private final Field clearButton;
    private final Map<AbstractMonitor, Boolean> recording = new WeakHashMap<>();
    private final Map<Class<?>, Field> baudRateFields = new HashMap<>();

    // what the last look found
    private AbstractMonitor shown;
    private String state;
    private String port;
    private Integer baud;
    private boolean uploading;
    private boolean closingRemotely;
    private volatile Map<String, Object> description;

    private SerialMonitorTap(final SerialLog log, final EditorInternals internals) throws ReflectiveOperationException {
        this.log = log;
        this.internals = internals;
        this.updateTimer = EditorInternals.accessible(AbstractMonitor.class.getDeclaredField("updateTimer"));
        this.consumeUpdateBuffer = EditorInternals.accessible(AbstractMonitor.class.getDeclaredMethod("consumeUpdateBuffer"));
        this.clearButton = EditorInternals.accessible(AbstractTextMonitor.class.getDeclaredField("clearButton"));
    }

    /**
     * Starts following the IDE's Serial Monitor and Plotter; once per IDE.
     */
    public static SerialMonitorTap start(final SerialLog log) throws Exception {
        SerialMonitorTap tap = new SerialMonitorTap(log, EditorInternals.get());
        // notices a monitor's window as it opens, before much of its text could slip by
        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (event.getID() == WindowEvent.WINDOW_OPENED && event.getSource() instanceof AbstractMonitor) tap.poll();
        }, AWTEvent.WINDOW_EVENT_MASK);
        new Timer(POLL_MS, e -> tap.poll()).start();
        return tap;
    }

    public SerialLog log() {
        return log;
    }

    EditorInternals internals() {
        return internals;
    }

    /**
     * @return the shown monitor's window, port, baud rate and state (open, or paused e.g. during an upload); null when
     * neither the Serial Monitor nor the Serial Plotter is shown. Callable from any thread.
     */
    public Map<String, Object> describe() {
        Map<String, Object> current = description;
        return current == null ? null : new LinkedHashMap<>(current);
    }

    /**
     * Tells the next look that the window about to close gets closed by a remote command, not by the user.
     */
    void closingRemotely(final boolean value) {
        closingRemotely = value;
    }

    /**
     * @return the monitor or plotter whose window is shown, or null
     */
    AbstractMonitor shownWindow() {
        AbstractMonitor monitor = internals.serialMonitor();
        if (monitor != null && monitor.isVisible()) return monitor;
        AbstractMonitor plotter = internals.serialPlotter();
        return plotter != null && plotter.isVisible() ? plotter : null;
    }

    static String nameOf(final AbstractMonitor monitor) {
        return monitor instanceof SerialPlotter ? SERIAL_PLOTTER : SERIAL_MONITOR;
    }

    static String addressOf(final AbstractMonitor monitor) {
        return monitor.getBoardPort() != null ? monitor.getBoardPort().getAddress() : null;
    }

    /**
     * Records what changed since the last look.
     */
    void poll() {
        try {
            boolean nowUploading = Editor.isUploading();
            if (nowUploading != uploading) {
                uploading = nowUploading;
                if (uploading) {
                    log.markUpload("upload started");
                } else {
                    log.event("upload finished");
                }
            }

            AbstractMonitor window = shownWindow();
            if (window != null) attach(window);
            String newState = window == null ? null : window.isClosed() ? PAUSED : OPEN;
            String newPort = window == null ? null : addressOf(window);
            Integer newBaud = window == null ? null : baudOf(window);

            if (window != shown) {
                // the IDE replaces the monitor when it reopens it, e.g. on another port
                boolean reopened = shown != null && window != null && nameOf(window).equals(nameOf(shown));
                if (shown != null && !reopened) {
                    log.event("closed " + (closingRemotely ? "remotely" : "in the IDE") + ": " + nameOf(shown));
                }
                if (window != null) {
                    log.event((reopened ? "reopened " : "opened ") + nameOf(window) + " on " + newPort + at(newBaud)
                            + (PAUSED.equals(newState) ? "; paused" + whyPaused(newPort) : ""));
                }
                closingRemotely = false;
            } else if (window != null) {
                if (!Objects.equals(newState, state)) {
                    log.event(OPEN.equals(newState) ? "resumed on " + newPort + at(newBaud) : "paused" + whyPaused(newPort));
                } else if (!Objects.equals(newPort, port) || !Objects.equals(newBaud, baud)) {
                    log.event("now on " + newPort + at(newBaud));
                }
            }

            shown = window;
            state = newState;
            port = newPort;
            baud = newBaud;

            Map<String, Object> newDescription = describe(window, newState, newPort, newBaud);
            if (!Objects.equals(newDescription, description)) {
                description = newDescription;
                log.setMonitor(newDescription);
            }
        } catch (RuntimeException e) {
            // never disturb the IDE; the next look tries again
        }
    }

    private Map<String, Object> describe(final AbstractMonitor window, final String windowState, final String address,
            final Integer baudRate) {
        if (window == null) return null;

        Map<String, Object> d = new LinkedHashMap<>();
        d.put("window", nameOf(window));
        d.put("port", address);
        d.put("baud", baudRate);
        d.put("state", windowState);
        if (!Boolean.TRUE.equals(recording.get(window))) d.put("recorded", false);
        return d;
    }

    /**
     * Starts recording what the monitor receives, and notices its Clear button; once per monitor.
     */
    private void attach(final AbstractMonitor monitor) {
        if (recording.containsKey(monitor)) return;

        boolean recorded = false;
        if (monitor instanceof SerialMonitor || monitor instanceof SerialPlotter) {
            try {
                Timer timer = (Timer) updateTimer.get(monitor);
                if (timer != null && Arrays.asList(timer.getActionListeners()).contains(monitor)) {
                    timer.removeActionListener(monitor);
                    timer.addActionListener(new Recorder(monitor, timer));
                    recorded = true;
                }
                if (monitor instanceof AbstractTextMonitor) {
                    JButton clear = (JButton) clearButton.get(monitor);
                    if (clear != null) clear.addActionListener(e -> log.clear("cleared in the IDE"));
                }
            } catch (IllegalAccessException | RuntimeException e) {
                // leave the monitor as it is
            }
        }
        recording.put(monitor, recorded);
    }

    /**
     * Takes the place of the monitor as its update timer's listener.
     */
    private final class Recorder implements ActionListener {
        private final AbstractMonitor monitor;
        private final Timer timer;

        Recorder(final AbstractMonitor monitor, final Timer timer) {
            this.monitor = monitor;
            this.timer = timer;
        }

        @Override
        public void actionPerformed(final ActionEvent e) {
            String text;
            try {
                text = (String) consumeUpdateBuffer.invoke(monitor);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // give the monitor its own listener back
                timer.removeActionListener(this);
                timer.addActionListener(monitor);
                recording.put(monitor, false);
                monitor.actionPerformed(e);
                return;
            }
            if (text == null || text.isEmpty()) return;

            try {
                poll(); // what happened to the monitor gets recorded before the text that follows it
                if (monitor == shown) log.received(text);
            } catch (RuntimeException ex) {
                // never keep the text from the monitor
            }
            monitor.message(text);
        }
    }

    private Integer baudOf(final AbstractMonitor monitor) {
        Field field = baudRateFields.computeIfAbsent(monitor.getClass(), type -> {
            try {
                return EditorInternals.accessible(type.getDeclaredField("serialRate"));
            } catch (NoSuchFieldException | RuntimeException e) {
                return null;
            }
        });
        try {
            return field != null ? field.getInt(monitor) : null;
        } catch (IllegalAccessException | RuntimeException e) {
            return null;
        }
    }

    private static String at(final Integer baudRate) {
        return baudRate != null ? " at " + baudRate + " baud" : "";
    }

    private static String whyPaused(final String address) {
        if (Editor.isUploading()) return " for the upload";
        return address != null && !UploadTargets.isAvailable(address) ? ": the port disappeared" : "";
    }
}
