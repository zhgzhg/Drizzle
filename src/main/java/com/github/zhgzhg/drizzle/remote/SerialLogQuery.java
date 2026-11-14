package com.github.zhgzhg.drizzle.remote;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a {@code serial-log} call asks the {@link SerialLog} for: which lines, and whether to wait for more.
 * Parsed from the call's arguments (see the ARG_ constants of {@link RemoteProtocol}); the CLI parses them too, to
 * report mistakes before reaching the IDE.
 */
public final class SerialLogQuery {
    public static final int DEFAULT_MAX_LINES = 200;
    public static final int MAX_LINES_LIMIT = 2000;
    public static final long DEFAULT_WAIT_SECONDS = 30;
    public static final long MAX_WAIT_SECONDS = 600;

    private static final Pattern DURATION = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|min|m|h)");
    private static final Pattern CLOCK_TIME = Pattern.compile("([0-9]{1,2}):([0-9]{2})(?::([0-9]{2})(?:\\.([0-9]{1,3}))?)?");
    private static final long HALF_A_DAY_MS = 12 * 3600_000L;

    /**
     * Where the requested lines start or end.
     */
    static final class Point {
        enum Kind {
            START,    // the oldest line kept
            NOW,      // the end of what arrived before the call
            UPLOAD,   // the last upload's start
            POSITION, // a line's position, e.g. the "next" of an earlier call
            TIME,     // a moment, in epoch milliseconds
            AGO,      // that many milliseconds before the call
            AFTER     // that many milliseconds after the start of the range (only for its end)
        }

        final Kind kind;
        final long value;

        Point(final Kind kind, final long value) {
            this.kind = kind;
            this.value = value;
        }

        boolean isTimeBased() {
            return kind == Kind.TIME || kind == Kind.AGO;
        }
    }

    final Point from;
    final Point to;
    final Pattern waitFor;
    final Long timeoutMs;
    final int maxLines;

    private SerialLogQuery(final Point from, final Point to, final Pattern waitFor, final Long timeoutMs, final int maxLines) {
        this.from = from;
        this.to = to;
        this.waitFor = waitFor;
        this.timeoutMs = timeoutMs;
        this.maxLines = maxLines;
    }

    /**
     * @param nowMs the current time, to place clock times such as 14:03:12 on the right day
     */
    public static SerialLogQuery fromArgs(final Map<String, String> args, final long nowMs, final ZoneId zone)
            throws RemoteException {

        Point from = point(args.get(RemoteProtocol.ARG_FROM), true, nowMs, zone);
        Point to = point(args.get(RemoteProtocol.ARG_TO), false, nowMs, zone);
        if (to != null && to.kind == Point.Kind.AFTER && from == null) {
            throw bad("--to +<duration> counts from --from; pass --from as well");
        }
        return new SerialLogQuery(from, to, regex(args.get(RemoteProtocol.ARG_WAIT_FOR_REGEX)),
                timeoutMs(args.get(RemoteProtocol.ARG_TIMEOUT)), maxLines(args.get(RemoteProtocol.ARG_MAX_LINES)));
    }

    /**
     * @return how long a call with these arguments may wait in the IDE, in seconds; 0 when it answers right away
     */
    public static long maxWaitSeconds(final Map<String, String> args) {
        String timeout = args.get(RemoteProtocol.ARG_TIMEOUT);
        if (timeout != null) {
            try {
                return Math.min(Long.parseLong(timeout.trim()), MAX_WAIT_SECONDS);
            } catch (NumberFormatException e) {
                return 0; // rejected by fromArgs anyway
            }
        }
        return args.containsKey(RemoteProtocol.ARG_WAIT_FOR_REGEX) || args.containsKey(RemoteProtocol.ARG_TO)
                ? DEFAULT_WAIT_SECONDS : 0;
    }

    private static Point point(final String text, final boolean isFrom, final long nowMs, final ZoneId zone)
            throws RemoteException {

        if (text == null) return null;
        String option = isFrom ? "--from" : "--to";
        String s = text.trim();
        String lower = s.toLowerCase(Locale.ROOT);

        switch (lower) {
            case "start":
            case "upload":
                if (!isFrom) throw bad("--to can't be " + lower + "; use a time, a line number or now");
                return new Point("start".equals(lower) ? Point.Kind.START : Point.Kind.UPLOAD, 0);
            case "now":
                return new Point(Point.Kind.NOW, 0);
            default:
                break;
        }

        if (s.matches("[0-9]+")) {
            try {
                return new Point(Point.Kind.POSITION, Long.parseLong(s));
            } catch (NumberFormatException e) {
                throw bad(option + " " + s + " is too big for a line number");
            }
        }

        if (s.startsWith("-") || s.startsWith("+")) {
            Matcher duration = DURATION.matcher(lower.substring(1));
            if (!duration.matches()) {
                throw bad(option + " " + s + ": expected a duration such as " + s.charAt(0) + "30s, "
                        + s.charAt(0) + "500ms, " + s.charAt(0) + "2m or " + s.charAt(0) + "1h");
            }
            long ms = durationMs(duration);
            if (s.startsWith("+")) {
                if (isFrom) throw bad("--from can't be in the future; use -<duration> for a time before now");
                return new Point(Point.Kind.AFTER, ms);
            }
            return new Point(Point.Kind.AGO, ms);
        }

        Matcher clock = CLOCK_TIME.matcher(s);
        if (clock.matches()) {
            return new Point(Point.Kind.TIME, clockTimeMs(clock, nowMs, zone, option, s));
        }

        try {
            LocalDateTime dateTime = LocalDateTime.parse(s.replace(' ', 'T'));
            return new Point(Point.Kind.TIME, dateTime.atZone(zone).toInstant().toEpochMilli());
        } catch (DateTimeParseException e) {
            throw bad("Unknown " + option + " value: " + s + ". Use " + (isFrom ? "start, upload, " : "")
                    + "now, a line number (the next of an earlier call), a time such as 14:03:12.345, a time relative"
                    + " to now such as -30s" + (isFrom ? "" : ", or a duration after --from such as +10s"));
        }
    }

    private static long durationMs(final Matcher duration) {
        double amount = Double.parseDouble(duration.group(1));
        switch (duration.group(2)) {
            case "ms": return Math.round(amount);
            case "s": return Math.round(amount * 1000);
            case "h": return Math.round(amount * 3600_000);
            default: return Math.round(amount * 60_000); // m, min
        }
    }

    /**
     * A time of day means the last time the clock showed it, give or take: up to 12 hours ahead counts as today.
     */
    private static long clockTimeMs(final Matcher clock, final long nowMs, final ZoneId zone, final String option,
            final String text) throws RemoteException {
        try {
            String fraction = clock.group(4) != null ? (clock.group(4) + "00").substring(0, 3) : "0";
            LocalTime time = LocalTime.of(Integer.parseInt(clock.group(1)), Integer.parseInt(clock.group(2)),
                    clock.group(3) != null ? Integer.parseInt(clock.group(3)) : 0,
                    Integer.parseInt(fraction) * 1_000_000);

            ZonedDateTime now = Instant.ofEpochMilli(nowMs).atZone(zone);
            long ms = now.with(time).toInstant().toEpochMilli();
            return ms - nowMs > HALF_A_DAY_MS ? now.minusDays(1).with(time).toInstant().toEpochMilli() : ms;
        } catch (DateTimeException e) {
            throw bad(option + " " + text + " isn't a valid time of day");
        }
    }

    private static Pattern regex(final String text) throws RemoteException {
        if (text == null) return null;
        if (text.isEmpty()) throw bad("--wait-for-regex needs a regular expression");
        try {
            return Pattern.compile(text);
        } catch (PatternSyntaxException e) {
            throw bad("--wait-for-regex isn't a valid regular expression: " + e.getDescription());
        }
    }

    private static Long timeoutMs(final String text) throws RemoteException {
        if (text == null) return null;
        try {
            long seconds = Long.parseLong(text.trim());
            if (seconds >= 0 && seconds <= MAX_WAIT_SECONDS) return seconds * 1000;
        } catch (NumberFormatException e) {
            // reported below
        }
        throw bad("--timeout for serial-log needs a number of seconds from 0 to " + MAX_WAIT_SECONDS);
    }

    private static int maxLines(final String text) throws RemoteException {
        if (text == null) return DEFAULT_MAX_LINES;
        try {
            int lines = Integer.parseInt(text.trim());
            if (lines >= 1 && lines <= MAX_LINES_LIMIT) return lines;
        } catch (NumberFormatException e) {
            // reported below
        }
        throw bad("--max-lines needs a number from 1 to " + MAX_LINES_LIMIT);
    }

    private static RemoteException bad(final String message) {
        return new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, message);
    }
}
