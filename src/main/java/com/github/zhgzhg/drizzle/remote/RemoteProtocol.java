package com.github.zhgzhg.drizzle.remote;

/**
 * Constants shared by the HTTP server running inside Arduino IDE and the CLI client.
 */
public final class RemoteProtocol {
    private RemoteProtocol() { }

    public static final int API_VERSION = 1;
    public static final int INSTANCE_FILE_FORMAT = 1;

    public static final String TOKEN_HEADER = "X-Drizzle-Token";
    public static final String STATUS_PATH = "/api/v1/status";
    public static final String JOBS_PATH = "/api/v1/jobs";
    public static final String CANCEL_SUFFIX = "/cancel";

    public static final String CMD_OPEN = "open";
    public static final String CMD_APPLY_MARKERS = "apply-markers";
    public static final String CMD_COMPILE = "compile";
    public static final String CMD_AUTOGEN_MARKERS = "autogen-markers";
    public static final String CMD_LIST_FQBNS = "list-fqbns";

    public static final String ARG_SKETCH = "sketch";
    public static final String ARG_VERBOSE = "verbose";
    public static final String ARG_MODE = "mode";

    public static final String JOB_RUNNING = "running";
    public static final String JOB_DONE = "done";
    public static final String JOB_FAILED = "failed";

    // error codes reported by the server
    public static final String ERR_BAD_REQUEST = "bad_request";
    public static final String ERR_UNAUTHORIZED = "unauthorized";
    public static final String ERR_FORBIDDEN = "forbidden";
    public static final String ERR_NOT_FOUND = "not_found";
    public static final String ERR_BUSY = "busy";
    public static final String ERR_CANCELLED = "cancelled";
    public static final String ERR_SKETCH_NOT_OPEN = "sketch_not_open";
    public static final String ERR_UNSAVED_CHANGES = "unsaved_changes";
    public static final String ERR_FAILED = "failed";
    public static final String ERR_IDE_UNRESPONSIVE = "ide_unresponsive";
    public static final String ERR_INTERNAL = "internal";

    // error codes produced by the client
    public static final String ERR_USAGE = "usage";
    public static final String ERR_NO_INSTANCE = "no_instance";
    public static final String ERR_AMBIGUOUS = "ambiguous";
    public static final String ERR_TIMEOUT = "timeout";
    public static final String ERR_COMMUNICATION = "communication";
}
