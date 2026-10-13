package com.github.zhgzhg.drizzle.remote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.ToNumberPolicy;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Type;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Finds the running Drizzle instances and talks to their HTTP API.
 */
public final class RemoteClient {
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() { }.getType();
    private static final int CONNECT_TIMEOUT_MS = 2000;
    // longer than the IDE's own 10 s limit for UI work, so a running Drizzle always answers within it
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_LOG_CHARS = 64 * 1024;

    private final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
            .create();
    private final InstanceDirectory directory;
    private final Map<InstanceInfo, String> unresponsive = new LinkedHashMap<>();

    public RemoteClient(final InstanceDirectory directory) {
        this.directory = directory;
    }

    /**
     * A Drizzle instance that answered, with its status.
     */
    public static final class LiveInstance {
        public final InstanceInfo info;
        public final Map<String, Object> status;

        LiveInstance(final InstanceInfo info, final Map<String, Object> status) {
            this.info = info;
            this.status = status;
        }

        public String id() {
            return info.instanceId;
        }

        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> sketches() {
            Object sketches = status.get("sketches");
            return sketches instanceof List ? (List<Map<String, Object>>) sketches : Collections.emptyList();
        }

        public Map<String, Object> sketchIn(final File folder) {
            for (Map<String, Object> sketch : sketches()) {
                Object sketchFolder = sketch.get("folder");
                if (sketchFolder != null && samePath(sketchFolder.toString(), folder.getPath())) {
                    return sketch;
                }
            }
            return null;
        }
    }

    /**
     * The answer from a server that doesn't speak Drizzle's protocol.
     */
    static final class NotDrizzleException extends IOException {
        NotDrizzleException(final String message) {
            super(message);
        }
    }

    private enum Outcome { LIVE, GONE, UNRESPONSIVE, SILENT }

    private static final class Probe {
        final Outcome outcome;
        final Map<String, Object> status;
        final String problem;

        Probe(final Outcome outcome, final Map<String, Object> status, final String problem) {
            this.outcome = outcome;
            this.status = status;
            this.problem = problem;
        }
    }

    /**
     * @return the instances found by the last {@link #discover()} that are running but didn't answer, with the reason
     */
    public Map<InstanceInfo, String> unresponsive() {
        return Collections.unmodifiableMap(unresponsive);
    }

    /**
     * Asks every instance file's IDE for its status, all at once. Files left behind by IDEs that are gone get deleted:
     * nothing listens on their port anymore, or something other than that Drizzle instance does.
     */
    public List<LiveInstance> discover() {
        unresponsive.clear();
        List<LiveInstance> live = new ArrayList<>();
        List<InstanceInfo> infos = directory.readAll();
        if (infos.isEmpty()) return live;

        ExecutorService probes = Executors.newFixedThreadPool(Math.min(infos.size(), 8), r -> {
            Thread t = new Thread(r, "drizzle-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<Probe>> results = new ArrayList<>();
            for (InstanceInfo info : infos) {
                results.add(probes.submit(() -> probe(info)));
            }

            for (int i = 0; i < infos.size(); ++i) {
                InstanceInfo info = infos.get(i);
                Probe probe;
                try {
                    probe = results.get(i).get();
                } catch (ExecutionException e) {
                    probe = new Probe(Outcome.UNRESPONSIVE, null, String.valueOf(e.getCause()));
                }

                switch (probe.outcome) {
                    case LIVE:
                        live.add(new LiveInstance(info, probe.status));
                        break;
                    case GONE:
                        directory.delete(info.file);
                        break;
                    case SILENT:
                        // a running Drizzle answers within its 10 s limit even when its UI is blocked, so this is
                        // most likely another program on a reused port; a frozen IDE rewrites its file once it recovers
                        directory.delete(info.file);
                        unresponsive.put(info, probe.problem);
                        break;
                    default:
                        unresponsive.put(info, probe.problem);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            probes.shutdownNow();
        }
        return live;
    }

    private Probe probe(final InstanceInfo info) {
        if (!isListening(info)) {
            return new Probe(Outcome.GONE, null, null);
        }

        try {
            Map<String, Object> status = status(info);
            return info.instanceId.equals(status.get("instance"))
                    ? new Probe(Outcome.LIVE, status, null)
                    : new Probe(Outcome.GONE, null, null); // another instance listens on that port now
        } catch (RemoteException e) {
            return RemoteProtocol.ERR_UNAUTHORIZED.equals(e.getCode())
                    ? new Probe(Outcome.GONE, null, null) // another instance listens on that port now
                    : new Probe(Outcome.UNRESPONSIVE, null, e.getMessage());
        } catch (ConnectException | NotDrizzleException e) {
            return new Probe(Outcome.GONE, null, null);
        } catch (SocketTimeoutException e) {
            return new Probe(Outcome.SILENT, null, "No answer within " + READ_TIMEOUT_MS / 1000 + " seconds");
        } catch (IOException e) {
            return new Probe(Outcome.UNRESPONSIVE, null, "No answer: " + e.getMessage());
        }
    }

    /**
     * Picks the instance to send a command to.
     *
     * @param instanceId   the instance the user asked for, or null
     * @param sketchFolder the sketch the command is about, or null
     * @param mustBeOpen   whether the sketch must already be open in the chosen instance
     */
    public LiveInstance select(final List<LiveInstance> live, final String instanceId, final File sketchFolder,
            final boolean mustBeOpen) throws RemoteException {

        if (instanceId != null) {
            LiveInstance chosen = live.stream().filter(li -> li.id().equals(instanceId)).findFirst().orElse(null);
            if (chosen == null) {
                if (live.isEmpty()) throw noInstanceOrUnresponsive();
                throw new RemoteException(RemoteProtocol.ERR_USAGE, "No running instance has the id " + instanceId
                        + ". Running instances: " + describe(live));
            }
            if (mustBeOpen && sketchFolder != null && chosen.sketchIn(sketchFolder) == null) {
                throw notOpen(sketchFolder);
            }
            return chosen;
        }

        if (sketchFolder != null) {
            List<LiveInstance> withSketch = live.stream().filter(li -> li.sketchIn(sketchFolder) != null).collect(Collectors.toList());
            if (withSketch.size() == 1) return withSketch.get(0);
            if (withSketch.size() > 1) throw ambiguous("The sketch is open in more than one IDE", withSketch);

            // an IDE that doesn't answer right now may be the one showing the sketch; its file says what it had open
            for (Map.Entry<InstanceInfo, String> e : unresponsive.entrySet()) {
                for (String sketchPath : e.getKey().sketches) {
                    File parent = new File(sketchPath).getParentFile();
                    if (parent != null && samePath(parent.getPath(), sketchFolder.getPath())) {
                        throw new RemoteException(RemoteProtocol.ERR_IDE_UNRESPONSIVE,
                                "The IDE showing the sketch doesn't respond: " + e.getValue());
                    }
                }
            }
            if (live.isEmpty()) throw noInstanceOrUnresponsive();
            if (mustBeOpen) throw notOpen(sketchFolder);
        }

        if (live.isEmpty()) throw noInstanceOrUnresponsive();
        if (live.size() == 1) return live.get(0);
        throw ambiguous("More than one IDE is running", live);
    }

    public Map<String, Object> status(final InstanceInfo info) throws IOException, RemoteException {
        return call(info, "GET", RemoteProtocol.STATUS_PATH, null);
    }

    /**
     * @return the created job
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> submit(final InstanceInfo info, final String command, final Map<String, String> args)
            throws IOException, RemoteException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("command", command);
        body.put("args", args);
        return (Map<String, Object>) call(info, "POST", RemoteProtocol.JOBS_PATH, body).get("job");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> job(final InstanceInfo info, final String jobId, final long logFrom) throws IOException, RemoteException {
        return (Map<String, Object>) call(info, "GET", RemoteProtocol.JOBS_PATH + "/" + jobId + "?logFrom=" + logFrom, null).get("job");
    }

    /**
     * @return the job, cancelled if it was still running
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> cancel(final InstanceInfo info, final String jobId) throws IOException, RemoteException {
        return (Map<String, Object>) call(info, "POST", RemoteProtocol.JOBS_PATH + "/" + jobId + RemoteProtocol.CANCEL_SUFFIX,
                Collections.emptyMap()).get("job");
    }

    /**
     * Polls a job until it finishes or the timeout passes.
     *
     * @param progress receives the job's output as it arrives; may be null
     * @return the job's last state; its {@code log} holds the tail of the whole output
     */
    public Map<String, Object> waitFor(final InstanceInfo info, final String jobId, final long timeoutMs, final PrintStream progress)
            throws IOException, RemoteException {

        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        long pollMs = 250;
        long logFrom = 0;
        int transientFailures = 0;
        StringBuilder log = new StringBuilder();
        boolean logTruncated = false;

        while (true) {
            Map<String, Object> job;
            try {
                job = job(info, jobId, logFrom);
                transientFailures = 0;
            } catch (ConnectException e) {
                throw e;
            } catch (IOException e) {
                if (++transientFailures > 3) throw e;
                sleep(pollMs);
                continue;
            }

            String chunk = String.valueOf(job.getOrDefault("log", ""));
            if (!chunk.isEmpty()) {
                if (progress != null) {
                    progress.print(chunk);
                    progress.flush();
                }
                log.append(chunk);
                if (log.length() > MAX_LOG_CHARS) {
                    log.delete(0, log.length() - MAX_LOG_CHARS);
                    logTruncated = true;
                }
            }
            logFrom = ((Number) job.getOrDefault("logEnd", logFrom)).longValue();

            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            boolean finished = !RemoteProtocol.JOB_RUNNING.equals(job.get("state"));
            if (finished || remainingMs <= 0) {
                job.put("log", log.toString());
                job.remove("logFrom");
                job.remove("logEnd");
                if (logTruncated) job.put("logTruncated", true);
                return job;
            }

            sleep(Math.max(1, Math.min(pollMs, remainingMs)));
            pollMs = Math.min(pollMs * 2, 1000);
        }
    }

    /**
     * @return the canonical path of a sketch folder or of a file inside it
     */
    public static File canonicalSketchPath(final String path) throws RemoteException {
        File file = new File(path).getAbsoluteFile();
        if (!file.exists()) {
            String hint = "";
            if (File.separatorChar == '\\' && path.indexOf('?') >= 0) {
                // Java 8 on Windows passes arguments through the system code page, which can't hold every character
                hint = ". If the real path has characters the system code page lacks, change into the sketch folder"
                        + " and pass --sketch . instead";
            }
            throw new RemoteException(RemoteProtocol.ERR_USAGE, "No such sketch file or folder: " + path + hint);
        }
        try {
            return file.getCanonicalFile();
        } catch (IOException e) {
            throw new RemoteException(RemoteProtocol.ERR_USAGE, "Can't resolve " + path + ": " + e.getMessage());
        }
    }

    public static File sketchFolderOf(final File canonicalSketchPath) {
        return canonicalSketchPath.isDirectory() ? canonicalSketchPath : canonicalSketchPath.getParentFile();
    }

    /**
     * A running IDE accepts connections even when it's too busy to answer (the OS does that). So a connection that's
     * refused or times out means nothing listens there anymore. Java 8 on Windows takes seconds to report a refused
     * loopback connection and often ends with a timeout instead, so both count.
     */
    private static boolean isListening(final InstanceInfo info) {
        try (Socket socket = new Socket(Proxy.NO_PROXY)) {
            socket.connect(new InetSocketAddress(info.host, info.port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException | IllegalArgumentException e) {
            return false;
        }
    }

    static boolean samePath(final String a, final String b) {
        return File.separatorChar == '\\' ? a.equalsIgnoreCase(b) : a.equals(b);
    }

    private Map<String, Object> call(final InstanceInfo info, final String method, final String path, final Map<String, Object> body)
            throws IOException, RemoteException {

        HttpURLConnection connection = (HttpURLConnection) new URL(info.baseUrl() + path).openConnection(Proxy.NO_PROXY);
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty(RemoteProtocol.TOKEN_HEADER, info.token);
            connection.setRequestProperty("Accept", "application/json");

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(gson.toJson(body).getBytes(StandardCharsets.UTF_8));
                }
            }

            int httpStatus = connection.getResponseCode();
            InputStream in = httpStatus >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = in == null ? "" : readAll(in);

            Map<String, Object> json;
            try {
                json = gson.fromJson(text, MAP_TYPE);
            } catch (JsonParseException e) {
                json = null;
            }
            if (json == null || !(json.get("ok") instanceof Boolean)) {
                throw new NotDrizzleException("Unexpected answer from " + info.baseUrl() + " (HTTP " + httpStatus + ")");
            }

            if (httpStatus >= 400 || Boolean.FALSE.equals(json.get("ok"))) {
                Object error = json.get("error");
                Map<?, ?> errorMap = error instanceof Map ? (Map<?, ?>) error : Collections.emptyMap();
                Object code = errorMap.get("code");
                Object message = errorMap.get("message");
                throw new RemoteException(code != null ? code.toString() : RemoteProtocol.ERR_COMMUNICATION,
                        message != null ? message.toString() : "HTTP " + httpStatus);
            }
            return json;
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(final InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static RemoteException noInstance() {
        return new RemoteException(RemoteProtocol.ERR_NO_INSTANCE,
                "No running Arduino IDE with Drizzle was found. Start Arduino IDE 1.8.19 with Drizzle installed.");
    }

    private RemoteException noInstanceOrUnresponsive() {
        if (!unresponsive.isEmpty()) {
            return new RemoteException(RemoteProtocol.ERR_IDE_UNRESPONSIVE, "Arduino IDE is running but doesn't respond: "
                    + unresponsive.values().iterator().next());
        }
        return noInstance();
    }

    private static RemoteException notOpen(final File sketchFolder) {
        return new RemoteException(RemoteProtocol.ERR_SKETCH_NOT_OPEN, "The sketch " + sketchFolder
                + " isn't open in any IDE. Open it with: drizzle --remote open --sketch \"" + sketchFolder + "\"");
    }

    private static RemoteException ambiguous(final String what, final List<LiveInstance> candidates) {
        return new RemoteException(RemoteProtocol.ERR_AMBIGUOUS, what + ". Choose one with --instance <id>: " + describe(candidates));
    }

    private static String describe(final List<LiveInstance> instances) {
        return instances.stream()
                .map(li -> li.id() + " (pid " + li.info.pid + ")")
                .collect(Collectors.joining(", "));
    }

    private static void sleep(final long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
