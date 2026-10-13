package com.github.zhgzhg.drizzle.remote;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * What a running Drizzle instance publishes about itself in its instance file: how to reach its HTTP API and which
 * sketches it has open. The sketch list is a hint only; clients confirm it through the status endpoint.
 */
public final class InstanceInfo {
    public int format = RemoteProtocol.INSTANCE_FILE_FORMAT;
    public int apiVersion = RemoteProtocol.API_VERSION;
    public String instanceId;
    public long pid;
    public String host;
    public int port;
    public String token;
    public String drizzleVersion;
    public String ideVersion;
    public String startedAt;
    public List<String> sketches = new ArrayList<>();

    /** The file this information was read from; null when it wasn't read from a file. */
    public transient Path file;

    public String baseUrl() {
        return "http://" + (host.contains(":") ? "[" + host + "]" : host) + ":" + port;
    }

    public Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("format", Integer.toString(format));
        p.setProperty("api", Integer.toString(apiVersion));
        p.setProperty("instance", instanceId);
        p.setProperty("pid", Long.toString(pid));
        p.setProperty("host", host);
        p.setProperty("port", Integer.toString(port));
        p.setProperty("token", token);
        if (drizzleVersion != null) p.setProperty("drizzle", drizzleVersion);
        if (ideVersion != null) p.setProperty("ide", ideVersion);
        if (startedAt != null) p.setProperty("started", startedAt);
        for (int i = 0; i < sketches.size(); ++i) {
            p.setProperty("sketch." + i, sketches.get(i));
        }
        return p;
    }

    /**
     * @throws IllegalArgumentException when a required entry is missing or malformed
     */
    public static InstanceInfo fromProperties(final Properties p) {
        InstanceInfo info = new InstanceInfo();
        info.format = Integer.parseInt(required(p, "format"));
        info.apiVersion = Integer.parseInt(required(p, "api"));
        info.instanceId = required(p, "instance");
        info.pid = Long.parseLong(required(p, "pid"));
        info.host = required(p, "host");
        info.port = Integer.parseInt(required(p, "port"));
        if (info.port < 1 || info.port > 65535) {
            throw new IllegalArgumentException("Invalid port " + info.port);
        }
        info.token = required(p, "token");
        info.drizzleVersion = p.getProperty("drizzle");
        info.ideVersion = p.getProperty("ide");
        info.startedAt = p.getProperty("started");
        for (int i = 0; p.getProperty("sketch." + i) != null; ++i) {
            info.sketches.add(p.getProperty("sketch." + i));
        }
        return info;
    }

    private static String required(final Properties p, final String key) {
        String value = p.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Missing \"" + key + "\"");
        }
        return value.trim();
    }
}
