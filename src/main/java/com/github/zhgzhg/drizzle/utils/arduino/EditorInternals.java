package com.github.zhgzhg.drizzle.utils.arduino;

import cc.arduino.packages.BoardPort;
import com.github.zhgzhg.drizzle.remote.RemoteException;
import com.github.zhgzhg.drizzle.remote.RemoteProtocol;
import processing.app.AbstractMonitor;
import processing.app.Base;
import processing.app.BaseNoGui;
import processing.app.Editor;

import javax.swing.*;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The private parts of Arduino IDE 1.8.19's editor that its own upload and Serial Monitor rely on; remote control only
 * runs there. The Serial Monitor and Plotter are shared by all editor windows.
 */
final class EditorInternals {
    private final Field uploading;
    private final Field serialMonitor;
    private final Field serialPlotter;
    private final Method resumeOrCloseSerialMonitor;
    private final Method resumeOrCloseSerialPlotter;
    private final Method selectSerialPort;

    private EditorInternals() throws ReflectiveOperationException {
        this.uploading = accessible(Editor.class.getDeclaredField("uploading"));
        this.serialMonitor = accessible(Editor.class.getDeclaredField("serialMonitor"));
        this.serialPlotter = accessible(Editor.class.getDeclaredField("serialPlotter"));
        this.resumeOrCloseSerialMonitor = accessible(Editor.class.getDeclaredMethod("resumeOrCloseSerialMonitor"));
        this.resumeOrCloseSerialPlotter = accessible(Editor.class.getDeclaredMethod("resumeOrCloseSerialPlotter"));
        this.selectSerialPort = accessible(Editor.class.getDeclaredMethod("selectSerialPort", String.class));
    }

    static EditorInternals get() throws RemoteException {
        try {
            return new EditorInternals();
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new RemoteException(RemoteProtocol.ERR_FAILED, "Drizzle's remote control doesn't work with this Arduino IDE: " + e);
        }
    }

    void setUploading(final boolean value) {
        try {
            this.uploading.setBoolean(null, value);
        } catch (IllegalAccessException e) {
            e.printStackTrace(); // made accessible already
        }
    }

    /**
     * @return the Serial Monitor the IDE created last, whether its window is shown or not; null if none
     */
    AbstractMonitor serialMonitor() {
        return monitorIn(this.serialMonitor);
    }

    /**
     * @return the Serial Plotter the IDE created last, whether its window is shown or not; null if none
     */
    AbstractMonitor serialPlotter() {
        return monitorIn(this.serialPlotter);
    }

    void suspendMonitors() throws Exception {
        for (AbstractMonitor monitor : new AbstractMonitor[] { serialMonitor(), serialPlotter() }) {
            if (monitor != null) monitor.suspend();
        }
    }

    /**
     * Reopens their port, or closes them when the port doesn't come back; reports problems in the status bar.
     */
    void resumeMonitors(final Editor editor) {
        for (Method method : new Method[] { this.resumeOrCloseSerialMonitor, this.resumeOrCloseSerialPlotter }) {
            try {
                method.invoke(editor);
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Selects the port as Tools &rarr; Port does, which also reopens a visible Serial Monitor on the new port.
     * Call on a thread other than the event dispatch thread.
     *
     * @return the port's address as the IDE knows it
     */
    String selectPort(final Editor editor, final String address) throws Exception {
        BoardPort port = UploadTargets.findPort(address);
        if (port == null) {
            throw new RemoteException(RemoteProtocol.ERR_PORT_UNAVAILABLE, "The port " + address + " isn't available. "
                    + UploadTargets.availablePortsSentence());
        }
        String portAddress = port.getAddress();
        if (portAddress.equals(UploadTargets.selectedPortAddress())) return portAddress;

        SwingUtilities.invokeAndWait(() -> {
            try {
                this.selectSerialPort.invoke(editor, portAddress);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("Couldn't select the port " + portAddress, e);
            }
        });
        if (!portAddress.equals(UploadTargets.selectedPortAddress())) {
            BaseNoGui.selectSerialPort(portAddress); // the IDE didn't, as its Port menu doesn't exist yet
            SwingUtilities.invokeLater(() -> {
                if (Base.INSTANCE != null) Base.INSTANCE.onBoardOrPortChange();
            });
        }
        return portAddress;
    }

    private static AbstractMonitor monitorIn(final Field field) {
        try {
            return (AbstractMonitor) field.get(null);
        } catch (IllegalAccessException e) {
            return null; // made accessible already
        }
    }

    static <T extends AccessibleObject> T accessible(final T member) {
        member.setAccessible(true);
        return member;
    }
}
