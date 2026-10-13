package com.github.zhgzhg.drizzle.remote;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code drizzle --remote <command> ...}: sends commands to a running Arduino IDE with Drizzle and prints the
 * outcome as one JSON object on stdout. The exit code tells the caller what to do next (see the EXIT_* constants).
 */
public final class RemoteCLI {
    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_NO_INSTANCE = 3;
    public static final int EXIT_SKETCH_NOT_OPEN = 4;
    public static final int EXIT_BUSY = 5;
    public static final int EXIT_UNSAVED_CHANGES = 6;
    public static final int EXIT_TIMEOUT = 7;
    public static final int EXIT_COMMUNICATION = 8;

    static final long DEFAULT_TIMEOUT_SECONDS = 600;

    private static final List<String> JOB_COMMANDS = Arrays.asList(RemoteProtocol.CMD_APPLY_MARKERS,
            RemoteProtocol.CMD_COMPILE, RemoteProtocol.CMD_AUTOGEN_MARKERS, RemoteProtocol.CMD_LIST_FQBNS);

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private final RemoteClient client;
    private final PrintStream out;
    private final PrintStream err;

    public RemoteCLI(final RemoteClient client, final PrintStream out, final PrintStream err) {
        this.client = client;
        this.out = out;
        this.err = err;
    }

    public static int run(final String[] args) {
        return new RemoteCLI(new RemoteClient(InstanceDirectory.defaultLocation()), System.out, System.err).execute(args);
    }

    public static String usage(final String launcher) {
        return String.format(
                  "\t%1$s --remote list%n"
                + "\t%1$s --remote status [--sketch <path>] [--instance <id>]%n"
                + "\t%1$s --remote open --sketch <path> [--instance <id>]%n"
                + "\t%1$s --remote apply-markers --sketch <path>%n"
                + "\t%1$s --remote compile --sketch <path> [--verbose]%n"
                + "\t%1$s --remote autogen-markers --sketch <path> [--mode board|all]%n"
                + "\t%1$s --remote list-fqbns [--sketch <path> | --instance <id>]%n"
                + "\t%1$s --remote job <job-id> [--instance <id>] [--wait]%n"
                + "\t%1$s --remote cancel <job-id> [--instance <id>]%n"
                + "%n"
                + "\tCommands that run in the IDE also accept --timeout <seconds> (default %2$d), --no-wait, and%n"
                + "\t--progress (copies the IDE's output to stderr while waiting).%n"
                + "\tExit codes: 0 ok, 1 failed or cancelled, 2 usage, 3 no IDE running, 4 sketch not open, 5 busy,%n"
                + "\t6 unsaved changes, 7 timeout (the command keeps running), 8 IDE not responding or communication error.%n",
                launcher, DEFAULT_TIMEOUT_SECONDS);
    }

    public int execute(final String[] args) {
        try {
            Options options = Options.parse(args);
            switch (options.command) {
                case "list": return list();
                case "status": return status(options);
                case RemoteProtocol.CMD_OPEN: return open(options);
                case "job": return job(options);
                case "cancel": return cancel(options);
                default: return runJob(options);
            }
        } catch (RemoteException e) {
            return printError(e.getCode(), e.getMessage());
        } catch (IOException e) {
            return printError(RemoteProtocol.ERR_COMMUNICATION, "Can't talk to the IDE: " + e);
        }
    }

    private int list() {
        List<Map<String, Object>> instances = new ArrayList<>();
        List<RemoteClient.LiveInstance> live = client.discover();
        for (RemoteClient.LiveInstance li : live) {
            Map<String, Object> instance = new LinkedHashMap<>(li.status);
            instance.remove("ok");
            instance.put("pid", li.info.pid);
            instances.add(instance);
        }
        for (Map.Entry<InstanceInfo, String> e : client.unresponsive().entrySet()) {
            Map<String, Object> instance = new LinkedHashMap<>();
            instance.put("instance", e.getKey().instanceId);
            instance.put("pid", e.getKey().pid);
            instance.put("responding", false);
            instance.put("problem", e.getValue());
            instances.add(instance);
        }

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ok", !live.isEmpty());
        json.put("instances", instances);
        if (live.isEmpty()) {
            try {
                client.select(live, null, null, false);
            } catch (RemoteException e) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("code", e.getCode());
                error.put("message", e.getMessage());
                json.put("error", error);
                print(json);
                return exitCodeOf(e.getCode());
            }
        }
        print(json);
        return EXIT_OK;
    }

    private int status(final Options options) throws RemoteException {
        File sketch = options.sketch != null ? RemoteClient.canonicalSketchPath(options.sketch) : null;
        File folder = sketch != null ? RemoteClient.sketchFolderOf(sketch) : null;
        RemoteClient.LiveInstance chosen = client.select(client.discover(), options.instance, folder, false);

        Map<String, Object> json = new LinkedHashMap<>(chosen.status);
        if (folder != null) {
            Map<String, Object> openSketch = chosen.sketchIn(folder);
            json.put("sketch", openSketch);
            if (openSketch == null) json.put("sketchOpen", false);
        }
        print(json);
        return EXIT_OK;
    }

    private int open(final Options options) throws RemoteException, IOException {
        File sketch = RemoteClient.canonicalSketchPath(options.requireSketch());
        File folder = RemoteClient.sketchFolderOf(sketch);
        List<RemoteClient.LiveInstance> live = client.discover();

        if (options.instance == null) {
            for (RemoteClient.LiveInstance li : live) {
                Map<String, Object> openSketch = li.sketchIn(folder);
                if (openSketch != null) {
                    Map<String, Object> json = new LinkedHashMap<>();
                    json.put("ok", true);
                    json.put("instance", li.id());
                    json.put("alreadyOpen", true);
                    json.put("sketch", openSketch);
                    print(json);
                    return EXIT_OK;
                }
            }
        }

        RemoteClient.LiveInstance chosen = client.select(live, options.instance, folder, false);
        Map<String, String> args = new LinkedHashMap<>();
        args.put(RemoteProtocol.ARG_SKETCH, sketch.getPath());
        return submitAndReport(chosen, RemoteProtocol.CMD_OPEN, args, options);
    }

    private int runJob(final Options options) throws RemoteException, IOException {
        if (!JOB_COMMANDS.contains(options.command)) {
            throw new RemoteException(RemoteProtocol.ERR_USAGE, "Unknown remote command: " + options.command);
        }

        boolean needsSketch = !RemoteProtocol.CMD_LIST_FQBNS.equals(options.command);
        String sketchArg = needsSketch ? options.requireSketch() : options.sketch;
        File sketch = sketchArg != null ? RemoteClient.canonicalSketchPath(sketchArg) : null;
        File folder = sketch != null ? RemoteClient.sketchFolderOf(sketch) : null;

        RemoteClient.LiveInstance chosen = client.select(client.discover(), options.instance, folder, needsSketch);

        Map<String, String> args = new LinkedHashMap<>();
        if (sketch != null) args.put(RemoteProtocol.ARG_SKETCH, sketch.getPath());
        if (options.verbose) args.put(RemoteProtocol.ARG_VERBOSE, "true");
        if (options.mode != null) args.put(RemoteProtocol.ARG_MODE, options.mode);
        return submitAndReport(chosen, options.command, args, options);
    }

    private int job(final Options options) throws RemoteException, IOException {
        RemoteClient.LiveInstance instance = instanceWithJob(options);
        long timeoutMs = options.wait ? options.timeoutSeconds * 1000 : 0;
        return report(instance, client.waitFor(instance.info, options.jobId, timeoutMs, options.progress ? err : null));
    }

    /**
     * Succeeds when the job isn't running anymore: it got cancelled, or it had already finished.
     */
    private int cancel(final Options options) throws RemoteException, IOException {
        RemoteClient.LiveInstance instance = instanceWithJob(options);
        client.cancel(instance.info, options.jobId);

        // the final state, with the output gathered until the cancel
        Map<String, Object> json = reportOf(instance, client.waitFor(instance.info, options.jobId, 0, null));
        boolean stopped = !RemoteProtocol.JOB_RUNNING.equals(json.get("state"));
        json.put("ok", stopped);
        print(json);
        return stopped ? EXIT_OK : EXIT_COMMUNICATION;
    }

    private RemoteClient.LiveInstance instanceWithJob(final Options options) throws RemoteException, IOException {
        if (options.jobId == null) {
            throw new RemoteException(RemoteProtocol.ERR_USAGE, "Missing the job id: --remote " + options.command + " <job-id>");
        }

        List<RemoteClient.LiveInstance> live = client.discover();
        if (options.instance != null) {
            live = new ArrayList<>(Arrays.asList(client.select(live, options.instance, null, false)));
        } else if (live.isEmpty()) {
            client.select(live, null, null, false); // reports that no instance runs
        }

        for (RemoteClient.LiveInstance li : live) {
            try {
                client.job(li.info, options.jobId, 0);
                return li;
            } catch (RemoteException e) {
                if (!RemoteProtocol.ERR_NOT_FOUND.equals(e.getCode())) throw e;
            }
        }

        throw new RemoteException(RemoteProtocol.ERR_USAGE, "No running instance knows job " + options.jobId
                + "; only the last 20 jobs of each IDE are kept");
    }

    private int submitAndReport(final RemoteClient.LiveInstance instance, final String command, final Map<String, String> args,
            final Options options) throws IOException, RemoteException {

        Map<String, Object> job = client.submit(instance.info, command, args);
        String jobId = String.valueOf(job.get("id"));
        err.printf("drizzle: %s started as job %s in instance %s%n", command, jobId, instance.id());

        if (options.noWait) {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("ok", true);
            json.put("instance", instance.id());
            json.put("job", jobId);
            json.put("command", command);
            json.put("state", RemoteProtocol.JOB_RUNNING);
            print(json);
            return EXIT_OK;
        }

        return report(instance, client.waitFor(instance.info, jobId, options.timeoutSeconds * 1000, options.progress ? err : null));
    }

    private int report(final RemoteClient.LiveInstance instance, final Map<String, Object> job) {
        Map<String, Object> json = reportOf(instance, job);
        print(json);

        if (Boolean.TRUE.equals(json.get("ok"))) return EXIT_OK;
        Object error = json.get("error");
        return error instanceof Map ? exitCodeOf(String.valueOf(((Map<?, ?>) error).get("code"))) : EXIT_FAILED;
    }

    /**
     * {@code ok} is true when the job finished and its result doesn't say otherwise.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> reportOf(final RemoteClient.LiveInstance instance, final Map<String, Object> job) {
        String state = String.valueOf(job.get("state"));
        Map<String, Object> result = (Map<String, Object>) job.get("result");
        Map<String, Object> error = (Map<String, Object>) job.get("error");

        boolean done = RemoteProtocol.JOB_DONE.equals(state);
        boolean ok = done && (result == null || !Boolean.FALSE.equals(result.get("ok")));

        if (RemoteProtocol.JOB_RUNNING.equals(state)) {
            error = new LinkedHashMap<>();
            error.put("code", RemoteProtocol.ERR_TIMEOUT);
            error.put("message", "Still running in the IDE. Check it later with: drizzle --remote job " + job.get("id")
                    + " --wait");
        }

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ok", ok);
        json.put("instance", instance.id());
        json.put("job", job.get("id"));
        json.put("command", job.get("command"));
        json.put("state", state);
        if (result != null) json.put("result", result);
        if (error != null) json.put("error", error);
        json.put("log", job.get("log"));
        if (job.containsKey("logTruncated")) json.put("logTruncated", true);
        return json;
    }

    private int printError(final String code, final String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("ok", false);
        json.put("error", error);
        print(json);
        return exitCodeOf(code);
    }

    private void print(final Map<String, Object> json) {
        out.println(asciiOnly(gson.toJson(json)));
        out.flush();
    }

    /**
     * Escapes every non-ASCII character as {@code \}{@code uXXXX}, so the JSON reaches the caller intact whatever
     * charset stdout uses (on Windows, Java 8 prints in the ANSI code page). Outside strings, JSON has no non-ASCII.
     */
    static String asciiOnly(final String json) {
        StringBuilder sb = null;
        for (int i = 0; i < json.length(); ++i) {
            char c = json.charAt(i);
            if (c < 0x80) {
                if (sb != null) sb.append(c);
                continue;
            }
            if (sb == null) sb = new StringBuilder(json.length() + 16).append(json, 0, i);
            sb.append(String.format("\\u%04x", (int) c));
        }
        return sb == null ? json : sb.toString();
    }

    static int exitCodeOf(final String code) {
        switch (code) {
            case RemoteProtocol.ERR_FAILED:
            case RemoteProtocol.ERR_CANCELLED: return EXIT_FAILED;
            case RemoteProtocol.ERR_USAGE:
            case RemoteProtocol.ERR_BAD_REQUEST:
            case RemoteProtocol.ERR_AMBIGUOUS:
            case RemoteProtocol.ERR_NOT_FOUND: return EXIT_USAGE;
            case RemoteProtocol.ERR_NO_INSTANCE: return EXIT_NO_INSTANCE;
            case RemoteProtocol.ERR_SKETCH_NOT_OPEN: return EXIT_SKETCH_NOT_OPEN;
            case RemoteProtocol.ERR_BUSY: return EXIT_BUSY;
            case RemoteProtocol.ERR_UNSAVED_CHANGES: return EXIT_UNSAVED_CHANGES;
            case RemoteProtocol.ERR_TIMEOUT: return EXIT_TIMEOUT;
            default: return EXIT_COMMUNICATION;
        }
    }

    static final class Options {
        String command;
        String jobId;
        String sketch;
        String instance;
        String mode;
        long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        boolean verbose;
        boolean noWait;
        boolean wait;
        boolean progress;

        static Options parse(final String[] args) throws RemoteException {
            if (args.length == 0) {
                throw new RemoteException(RemoteProtocol.ERR_USAGE, "Missing the remote command; see --help");
            }

            Options o = new Options();
            o.command = args[0];
            for (int i = 1; i < args.length; ++i) {
                String arg = args[i];
                switch (arg) {
                    case "--sketch": o.sketch = valueOf(args, ++i, arg); break;
                    case "--instance": o.instance = valueOf(args, ++i, arg); break;
                    case "--mode":
                        o.mode = valueOf(args, ++i, arg);
                        if (!"board".equals(o.mode) && !"all".equals(o.mode)) {
                            throw new RemoteException(RemoteProtocol.ERR_USAGE, "--mode must be board or all");
                        }
                        break;
                    case "--timeout":
                        try {
                            o.timeoutSeconds = Long.parseLong(valueOf(args, ++i, arg));
                        } catch (NumberFormatException e) {
                            throw new RemoteException(RemoteProtocol.ERR_USAGE, "--timeout needs a number of seconds");
                        }
                        if (o.timeoutSeconds < 0) {
                            throw new RemoteException(RemoteProtocol.ERR_USAGE, "--timeout can't be negative");
                        }
                        break;
                    case "--verbose": o.verbose = true; break;
                    case "--no-wait": o.noWait = true; break;
                    case "--wait": o.wait = true; break;
                    case "--progress": o.progress = true; break;
                    default:
                        if (("job".equals(o.command) || "cancel".equals(o.command)) && o.jobId == null && !arg.startsWith("--")) {
                            o.jobId = arg;
                        } else {
                            throw new RemoteException(RemoteProtocol.ERR_USAGE, "Unknown argument: " + arg);
                        }
                }
            }
            return o;
        }

        String requireSketch() throws RemoteException {
            if (sketch == null) {
                throw new RemoteException(RemoteProtocol.ERR_USAGE, command + " needs --sketch <path of the sketch folder or .ino>");
            }
            return sketch;
        }

        private static String valueOf(final String[] args, final int i, final String option) throws RemoteException {
            if (i >= args.length) {
                throw new RemoteException(RemoteProtocol.ERR_USAGE, option + " needs a value");
            }
            return args[i];
        }
    }
}
