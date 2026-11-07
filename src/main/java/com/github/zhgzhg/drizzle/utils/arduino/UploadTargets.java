package com.github.zhgzhg.drizzle.utils.arduino;

import cc.arduino.packages.BoardPort;
import cc.arduino.packages.Uploader;
import cc.arduino.packages.UploaderFactory;
import processing.app.BaseNoGui;
import processing.app.PreferencesData;
import processing.app.debug.TargetBoard;
import processing.app.debug.TargetPlatform;
import processing.app.helpers.PreferencesMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The ports and programmers Arduino IDE 1.8.19 offers in Tools &rarr; Port and Tools &rarr; Programmer, taken from
 * where the IDE builds those menus from. The Port menu itself is only rebuilt when someone opens the Tools menu.
 */
public final class UploadTargets {
    private UploadTargets() { }

    public static final String PORT_PREFERENCE = "serial.port";
    public static final String PROGRAMMER_PREFERENCE = "programmer";
    private static final String SHOW_ALL_PORTS_PREFERENCE = "serial.ports.showall";
    private static final List<String> PROTOCOL_ORDER = Arrays.asList("serial", "network");

    public static final class Programmer {
        public final String id;
        public final String name;

        Programmer(final String id, final String name) {
            this.id = id;
            this.name = name;
        }
    }

    /**
     * @return the ports the Port menu would list now, serial ones first
     */
    public static List<BoardPort> ports() {
        List<BoardPort> ports = BaseNoGui.getPlatform().filterPorts(BaseNoGui.getDiscoveryManager().discovery(),
                PreferencesData.getBoolean(SHOW_ALL_PORTS_PREFERENCE));
        ports.sort(Comparator.<BoardPort>comparingInt(p -> protocolRank(p.getProtocol()))
                .thenComparing(BoardPort::getAddress, Comparator.nullsLast(Comparator.naturalOrder())));
        return ports;
    }

    /**
     * @return the available port with that address (ignoring case, as Windows does with COM ports), or null
     */
    public static BoardPort findPort(final String address) {
        List<BoardPort> ports = ports();
        for (BoardPort port : ports) {
            if (Objects.equals(port.getAddress(), address)) return port;
        }
        for (BoardPort port : ports) {
            if (port.getAddress() != null && port.getAddress().equalsIgnoreCase(address)) return port;
        }
        return null;
    }

    /**
     * @return the address of the port selected in the IDE, or null
     */
    public static String selectedPortAddress() {
        String address = PreferencesData.get(PORT_PREFERENCE);
        return address == null || address.trim().isEmpty() ? null : address;
    }

    public static boolean isAvailable(final String address) {
        return address != null && ports().stream().anyMatch(port -> address.equals(port.getAddress()));
    }

    /**
     * @return the selected port, with whether it's available now; null when none is selected
     */
    public static Map<String, Object> describeSelectedPort() {
        String address = selectedPortAddress();
        if (address == null) return null;

        BoardPort port = ports().stream().filter(p -> address.equals(p.getAddress())).findFirst().orElse(null);
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("address", address);
        if (port != null) {
            description.put("label", label(port));
            description.put("protocol", port.getProtocol());
        }
        description.put("available", port != null);
        return description;
    }

    public static List<Map<String, Object>> describePorts() {
        String selected = selectedPortAddress();
        return ports().stream().map(port -> describe(port, selected)).collect(Collectors.toList());
    }

    /**
     * @return e.g. "Available ports: COM3 (Arduino Uno), COM5."
     */
    public static String availablePortsSentence() {
        List<BoardPort> ports = ports();
        if (ports.isEmpty()) return "No ports are available; is the board connected?";
        return "Available ports: " + ports.stream().map(UploadTargets::label).collect(Collectors.joining(", ")) + ".";
    }

    private static Map<String, Object> describe(final BoardPort port, final String selectedAddress) {
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("address", port.getAddress());
        description.put("label", label(port));
        description.put("protocol", port.getProtocol());
        description.put("selected", Objects.equals(port.getAddress(), selectedAddress));

        if (isNotBlank(port.getBoardName())) description.put("board", port.getBoardName());

        if ("serial".equals(port.getProtocol())) {
            TargetBoard match = matchingBoard(port);
            if (match != null) description.put("fqbn", match.getFQBN());

            // Arduino's serial port lister reports 0000 for ports without USB ids (e.g. Bluetooth or built-in ports)
            String vid = port.getPrefs().get("vid");
            String pid = port.getPrefs().get("pid");
            if (isUsbId(vid) && isUsbId(pid)) {
                description.put("vid", vid);
                description.put("pid", pid);
            }
            String serialNumber = port.getPrefs().get("iserial");
            if (isNotBlank(serialNumber)) description.put("serialNumber", serialNumber);
        }

        if ("network".equals(port.getProtocol())) {
            description.put("passwordRequired", needsUnknownPassword(port));
        }
        return description;
    }

    /**
     * @return the port's text in the Port menu, e.g. "COM3 (Arduino Uno)"
     */
    public static String label(final BoardPort port) {
        String label = isNotBlank(port.getLabel()) ? port.getLabel() : port.getAddress();
        return isNotBlank(port.getBoardName()) ? label + " (" + port.getBoardName() + ")" : label;
    }

    /**
     * Uploading to such a port makes Arduino IDE ask for the board's password in a dialog. It remembers the password
     * until it exits, once an upload with it succeeded.
     */
    public static boolean needsUnknownPassword(final BoardPort port) {
        try {
            Uploader uploader = new UploaderFactory().newUploader(BaseNoGui.getTargetBoard(), port, false);
            return needsUnknownPassword(uploader);
        } catch (RuntimeException e) {
            return false; // a network port that doesn't say how to upload to it; the upload itself will tell
        }
    }

    public static boolean needsUnknownPassword(final Uploader uploader) {
        return uploader != null && uploader.requiresAuthorization() && !PreferencesData.has(uploader.getAuthorizationKey());
    }

    /**
     * @return the programmers the Programmer menu lists for the selected board: its platform's, then those of the
     * platform whose core it uses
     */
    public static List<Programmer> programmers() {
        TargetBoard board = BaseNoGui.getTargetBoard();
        if (board == null) return Collections.emptyList();

        List<Programmer> programmers = new ArrayList<>();
        addProgrammers(board.getContainerPlatform(), programmers);

        String core = board.getPreferences().get("build.core");
        if (core != null && core.contains(":")) {
            addProgrammers(BaseNoGui.getCurrentTargetPlatformFromPackage(core.split(":", 2)[0]), programmers);
        }
        return programmers;
    }

    private static void addProgrammers(final TargetPlatform platform, final List<Programmer> into) {
        if (platform == null) return;
        String packageId = platform.getContainerPackage().getId();
        for (Map.Entry<String, PreferencesMap> entry : platform.getProgrammers().entrySet()) {
            String id = packageId + ":" + entry.getKey();
            if (into.stream().noneMatch(p -> p.id.equals(id))) {
                into.add(new Programmer(id, entry.getValue().get("name")));
            }
        }
    }

    /**
     * @return the id of the programmer selected in the IDE, e.g. "arduino:usbasp", or null
     */
    public static String selectedProgrammerId() {
        String id = PreferencesData.get(PROGRAMMER_PREFERENCE);
        return id == null || id.trim().isEmpty() ? null : id;
    }

    /**
     * @return the selected programmer, or null when none is selected
     */
    public static Map<String, Object> describeSelectedProgrammer() {
        String id = selectedProgrammerId();
        if (id == null) return null;

        Map<String, Object> description = new LinkedHashMap<>();
        description.put("id", id);
        programmers().stream().filter(p -> p.id.equals(id)).findFirst().ifPresent(p -> description.put("name", p.name));
        return description;
    }

    public static List<Map<String, Object>> describeProgrammers() {
        String selected = selectedProgrammerId();
        return programmers().stream().map(p -> {
            Map<String, Object> description = new LinkedHashMap<>();
            description.put("id", p.id);
            description.put("name", p.name);
            description.put("selected", p.id.equals(selected));
            return description;
        }).collect(Collectors.toList());
    }

    /**
     * Boards without a bootloader (no upload.protocol) get uploaded through the selected programmer even by Upload.
     */
    public static boolean uploadUsesProgrammer() {
        PreferencesMap board = BaseNoGui.getBoardPreferences();
        return board != null && board.get("upload.protocol") == null;
    }

    private static TargetBoard matchingBoard(final BoardPort port) {
        try {
            return port.searchMatchingBoard();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static int protocolRank(final String protocol) {
        int rank = PROTOCOL_ORDER.indexOf(protocol);
        return rank < 0 ? PROTOCOL_ORDER.size() : rank;
    }

    private static boolean isUsbId(final String id) {
        return isNotBlank(id) && !id.matches("(?i)(0x)?0+");
    }

    private static boolean isNotBlank(final String s) {
        return s != null && !s.trim().isEmpty();
    }
}
