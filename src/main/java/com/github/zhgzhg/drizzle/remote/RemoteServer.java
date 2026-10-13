package com.github.zhgzhg.drizzle.remote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The HTTP API Drizzle exposes inside Arduino IDE. It listens on the loopback interface only and answers requests
 * carrying the instance's token. Requests made by web browsers are rejected: they carry an {@code Origin} or
 * {@code Sec-Fetch-Site} header, or (DNS rebinding) a {@code Host} header naming something other than loopback.
 * <ul>
 *     <li>{@code GET /api/v1/status} - the IDE's state</li>
 *     <li>{@code POST /api/v1/jobs} with {@code {"command": "...", "args": {...}}} - starts a command</li>
 *     <li>{@code GET /api/v1/jobs/<id>?logFrom=<offset>} - a command's state, result and output</li>
 *     <li>{@code POST /api/v1/jobs/<id>/cancel} - cancels a running command</li>
 * </ul>
 */
public final class RemoteServer {
    private static final int MAX_REQUEST_BYTES = 64 * 1024;

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final RemoteCommandHandler handler;
    private final JobManager jobs;
    private final String instanceId;
    private final String token;
    private final HttpServer server;
    private final ExecutorService executor;

    private RemoteServer(final RemoteCommandHandler handler, final String instanceId) throws IOException {
        this.handler = handler;
        this.jobs = new JobManager(handler);
        this.instanceId = instanceId;
        this.token = newToken();

        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "drizzle-remote-http");
            t.setDaemon(true);
            return t;
        });
        this.server.setExecutor(executor);
        this.server.createContext("/", this::handle);
    }

    /**
     * Starts listening on a free port of the loopback interface.
     */
    public static RemoteServer start(final RemoteCommandHandler handler, final String instanceId) throws IOException {
        RemoteServer remoteServer = new RemoteServer(handler, instanceId);
        remoteServer.server.start();
        return remoteServer;
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
        jobs.shutdown();
    }

    public String host() {
        return server.getAddress().getAddress().getHostAddress();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public String token() {
        return token;
    }

    public String instanceId() {
        return instanceId;
    }

    private void handle(final HttpExchange exchange) throws IOException {
        try {
            checkCaller(exchange);

            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();

            if (RemoteProtocol.STATUS_PATH.equals(path)) {
                if (!"GET".equals(method)) {
                    sendError(exchange, 405, RemoteProtocol.ERR_BAD_REQUEST, "Use GET");
                    return;
                }
                send(exchange, 200, status());
            } else if (RemoteProtocol.JOBS_PATH.equals(path)) {
                if (!"POST".equals(method)) {
                    sendError(exchange, 405, RemoteProtocol.ERR_BAD_REQUEST, "Use POST");
                    return;
                }
                submitJob(exchange);
            } else if (path.startsWith(RemoteProtocol.JOBS_PATH + "/") && path.endsWith(RemoteProtocol.CANCEL_SUFFIX)) {
                if (!"POST".equals(method)) {
                    sendError(exchange, 405, RemoteProtocol.ERR_BAD_REQUEST, "Use POST");
                    return;
                }
                String id = path.substring(RemoteProtocol.JOBS_PATH.length() + 1, path.length() - RemoteProtocol.CANCEL_SUFFIX.length());
                sendJob(exchange, jobs.cancel(id), 0);
            } else if (path.startsWith(RemoteProtocol.JOBS_PATH + "/")) {
                if (!"GET".equals(method)) {
                    sendError(exchange, 405, RemoteProtocol.ERR_BAD_REQUEST, "Use GET");
                    return;
                }
                getJob(exchange, path.substring(RemoteProtocol.JOBS_PATH.length() + 1));
            } else {
                sendError(exchange, 404, RemoteProtocol.ERR_NOT_FOUND, "No such endpoint: " + path);
            }
        } catch (RemoteException e) {
            sendError(exchange, httpStatusOf(e.getCode()), e.getCode(), e.getMessage());
        } catch (Exception e) {
            sendError(exchange, 500, RemoteProtocol.ERR_INTERNAL, e.toString());
        } finally {
            exchange.close();
        }
    }

    private void checkCaller(final HttpExchange exchange) throws RemoteException {
        if (exchange.getRequestHeaders().containsKey("Origin") || exchange.getRequestHeaders().containsKey("Sec-Fetch-Site")) {
            throw new RemoteException(RemoteProtocol.ERR_FORBIDDEN, "Requests from web pages aren't accepted");
        }

        String host = exchange.getRequestHeaders().getFirst("Host");
        if (host == null || !isLoopbackHost(host.trim().toLowerCase(Locale.ROOT))) {
            throw new RemoteException(RemoteProtocol.ERR_FORBIDDEN, "Unexpected Host header: " + host);
        }

        String presented = exchange.getRequestHeaders().getFirst(RemoteProtocol.TOKEN_HEADER);
        if (presented == null || !MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new RemoteException(RemoteProtocol.ERR_UNAUTHORIZED,
                    "Missing or wrong " + RemoteProtocol.TOKEN_HEADER + " header; take the token from this instance's file");
        }
    }

    private boolean isLoopbackHost(final String host) {
        String port = ":" + port();
        return host.equals("127.0.0.1" + port) || host.equals("localhost" + port) || host.equals("[::1]" + port)
                || host.equals(host() + port) || host.equals("[" + host() + "]" + port);
    }

    private Map<String, Object> status() throws RemoteException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ok", true);
        json.put("instance", instanceId);
        json.put("api", RemoteProtocol.API_VERSION);
        json.putAll(handler.status());

        String busy = ActivityGuard.busyDescription();
        json.put("busy", busy != null);
        if (busy != null) json.put("busyWith", busy);

        Job current = jobs.getCurrent();
        if (current != null) json.put("currentJob", current.summary());
        Job last = jobs.getLast();
        if (last != null) json.put("lastJob", last.summary());
        return json;
    }

    private void submitJob(final HttpExchange exchange) throws IOException, RemoteException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "Expected Content-Type: application/json");
        }

        Map<String, Object> body;
        try {
            body = gson.fromJson(readBody(exchange), new TypeToken<Map<String, Object>>() { }.getType());
        } catch (JsonParseException e) {
            throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "Malformed JSON: " + e.getMessage());
        }
        if (body == null || !(body.get("command") instanceof String)) {
            throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "Missing \"command\"");
        }

        Map<String, String> args = new LinkedHashMap<>();
        Object rawArgs = body.get("args");
        if (rawArgs instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) rawArgs).entrySet()) {
                if (e.getValue() != null) args.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        } else if (rawArgs != null) {
            throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "\"args\" must be an object");
        }

        Job job = jobs.submit((String) body.get("command"), args);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ok", true);
        json.put("instance", instanceId);
        json.put("job", job.toJson(0));
        send(exchange, 202, json);
    }

    private void getJob(final HttpExchange exchange, final String id) throws IOException, RemoteException {
        Job job = jobs.get(id);
        if (job == null) {
            throw new RemoteException(RemoteProtocol.ERR_NOT_FOUND, "No such job: " + id);
        }

        long logFrom = 0;
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("logFrom=")) {
                    try {
                        logFrom = Long.parseLong(param.substring("logFrom=".length()));
                    } catch (NumberFormatException e) {
                        throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "logFrom must be a number");
                    }
                }
            }
        }

        sendJob(exchange, job, logFrom);
    }

    private void sendJob(final HttpExchange exchange, final Job job, final long logFrom) throws IOException {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ok", true);
        json.put("instance", instanceId);
        json.put("job", job.toJson(logFrom));
        send(exchange, 200, json);
    }

    private String readBody(final HttpExchange exchange) throws IOException, RemoteException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try (InputStream in = exchange.getRequestBody()) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                body.write(buffer, 0, read);
                if (body.size() > MAX_REQUEST_BYTES) {
                    throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "Request body too large");
                }
            }
        }
        return new String(body.toByteArray(), StandardCharsets.UTF_8);
    }

    private void sendError(final HttpExchange exchange, final int httpStatus, final String code, final String message)
            throws IOException {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ok", false);
        json.put("instance", instanceId);
        json.put("error", error);
        send(exchange, httpStatus, json);
    }

    private void send(final HttpExchange exchange, final int httpStatus, final Map<String, Object> json) throws IOException {
        byte[] bytes = gson.toJson(json).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().put("Content-Type", Collections.singletonList("application/json; charset=utf-8"));
        exchange.getResponseHeaders().put("Cache-Control", Collections.singletonList("no-store"));
        exchange.sendResponseHeaders(httpStatus, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static int httpStatusOf(final String code) {
        switch (code) {
            case RemoteProtocol.ERR_BAD_REQUEST: return 400;
            case RemoteProtocol.ERR_UNAUTHORIZED: return 401;
            case RemoteProtocol.ERR_FORBIDDEN: return 403;
            case RemoteProtocol.ERR_NOT_FOUND: return 404;
            case RemoteProtocol.ERR_BUSY: return 409;
            case RemoteProtocol.ERR_IDE_UNRESPONSIVE: return 503;
            default: return 500;
        }
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
