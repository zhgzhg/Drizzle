package com.github.zhgzhg.drizzle.remote;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A command submitted through the HTTP API, with its state, result and output.
 */
public final class Job {
    static final int MAX_LOG_CHARS = 1 << 20;

    private final String id;
    private final String command;
    private final Map<String, String> args;
    private final String submittedAt = Instant.now().toString();

    private String state = RemoteProtocol.JOB_RUNNING;
    private String finishedAt;
    private Map<String, Object> result;
    private String errorCode;
    private String errorMessage;

    private final StringBuilder log = new StringBuilder();
    private long logDropped; // characters removed from the head of the log to keep it under MAX_LOG_CHARS

    Job(final String id, final String command, final Map<String, String> args) {
        this.id = id;
        this.command = command;
        this.args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }

    public String getId() {
        return id;
    }

    public String getCommand() {
        return command;
    }

    public Map<String, String> getArgs() {
        return args;
    }

    public synchronized String getState() {
        return state;
    }

    synchronized void appendLog(final String text) {
        if (text == null || text.isEmpty()) return;

        log.append(text);
        int excess = log.length() - MAX_LOG_CHARS;
        if (excess > 0) {
            log.delete(0, excess);
            logDropped += excess;
        }
    }

    synchronized boolean isRunning() {
        return RemoteProtocol.JOB_RUNNING.equals(state);
    }

    /**
     * Has no effect once the job finished, e.g. when a cancelled job's thread returns later.
     */
    synchronized void succeed(final Map<String, Object> result) {
        if (!isRunning()) return;
        this.result = result;
        this.state = RemoteProtocol.JOB_DONE;
        this.finishedAt = Instant.now().toString();
    }

    /**
     * Has no effect once the job finished, e.g. when a cancelled job's thread returns later.
     */
    synchronized void fail(final String code, final String message) {
        if (!isRunning()) return;
        this.errorCode = code;
        this.errorMessage = message;
        this.state = RemoteProtocol.JOB_FAILED;
        this.finishedAt = Instant.now().toString();
    }

    /**
     * @param logFrom the log offset the caller already has; only newer output is included
     */
    synchronized Map<String, Object> toJson(final long logFrom) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("command", command);
        json.put("args", args);
        json.put("state", state);
        json.put("submittedAt", submittedAt);
        if (finishedAt != null) json.put("finishedAt", finishedAt);
        if (result != null) json.put("result", result);
        if (errorCode != null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", errorCode);
            error.put("message", errorMessage);
            json.put("error", error);
        }

        long logEnd = logDropped + log.length();
        long start = Math.max(Math.min(logFrom, logEnd), logDropped);
        json.put("logFrom", start);
        json.put("logEnd", logEnd);
        json.put("log", log.substring((int) (start - logDropped)));
        return json;
    }

    /**
     * @return a summary without the log, for status reports
     */
    synchronized Map<String, Object> summary() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("command", command);
        json.put("state", state);
        json.put("submittedAt", submittedAt);
        if (finishedAt != null) json.put("finishedAt", finishedAt);
        return json;
    }
}
