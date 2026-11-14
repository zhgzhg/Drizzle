package com.github.zhgzhg.drizzle.remote;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * What the IDE's Serial Monitor (or Serial Plotter) received, as lines stamped with their arrival time, together with
 * what happened meanwhile: the monitor opened, paused for an upload, got closed, what was sent to the board. Kept in
 * memory up to a limit, dropping the oldest entries first.
 * <p>
 * Every entry has a position that never changes, so a caller can continue where it stopped. Text arrives in arbitrary
 * pieces; a line ends with a line break, or, without one (e.g. a prompt), once nothing got added to it for
 * {@link #QUIET_MS}, or once it has been growing for {@link #MAX_LINE_AGE_MS}. Only finished lines are handed out, so
 * a line never changes after a caller saw it.
 */
public final class SerialLog {
    public static final int DEFAULT_MAX_ENTRIES = 50_000;
    public static final int DEFAULT_MAX_CHARS = 2_000_000;
    static final long QUIET_MS = 250;
    static final long MAX_LINE_AGE_MS = 2000;
    static final int MAX_LINE_CHARS = 4096;
    static final int MAX_RESULT_CHARS = 64 * 1024;

    private static final DateTimeFormatter TIME_OF_DAY = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter DATE_AND_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    enum Kind {
        TEXT("text"), EVENT("event"), SENT("sent");

        final String key;

        Kind(final String key) {
            this.key = key;
        }
    }

    static final class Entry {
        final long position;
        final long time;
        final Kind kind;
        final String text;

        Entry(final long position, final long time, final Kind kind, final String text) {
            this.position = position;
            this.time = time;
            this.kind = kind;
            this.text = text;
        }
    }

    private final LongSupplier clock;
    private final ZoneId zone;
    private final int maxEntries;
    private final int maxChars;

    private final List<Entry> entries = new ArrayList<>();
    private int head;           // index of the oldest kept entry; the ones before it are dropped
    private long firstPosition; // the position of the oldest kept entry
    private long chars;         // held by the kept entries
    private long lastTime;

    private StringBuilder openLine; // the line still arriving, or null
    private long openLineStart;
    private long openLineLast;
    private boolean afterCarriageReturn;
    private boolean endedEarly; // the last line got ended before its line break arrived

    private long uploadPosition = -1;
    private long uploadTime;

    private Map<String, Object> monitor;
    private long closures;

    public SerialLog() {
        this(System::currentTimeMillis, ZoneId.systemDefault(), DEFAULT_MAX_ENTRIES, DEFAULT_MAX_CHARS);
    }

    SerialLog(final LongSupplier clock, final ZoneId zone, final int maxEntries, final int maxChars) {
        this.clock = clock;
        this.zone = zone;
        this.maxEntries = maxEntries;
        this.maxChars = maxChars;
    }

    public ZoneId zone() {
        return zone;
    }

    public long currentTimeMillis() {
        return clock.getAsLong();
    }

    /**
     * Records text the monitor received.
     */
    public synchronized void received(final String text) {
        if (text == null || text.isEmpty()) return;

        long now = now();
        settle(now);
        for (int i = 0; i < text.length(); ++i) {
            char c = text.charAt(i);
            if (afterCarriageReturn) {
                afterCarriageReturn = false;
                if (c == '\n') continue; // the second half of CR LF
            }
            if (c == '\r' || c == '\n') {
                if (endedEarly) {
                    endedEarly = false; // the line break of the line already handed out
                } else {
                    endLine(now);
                }
                afterCarriageReturn = c == '\r';
                continue;
            }
            endedEarly = false;
            if (openLine == null) {
                openLine = new StringBuilder();
                openLineStart = now;
            }
            openLine.append(c);
            if (openLine.length() >= MAX_LINE_CHARS) {
                endLine(now);
                endedEarly = true;
            }
        }
        if (openLine != null) openLineLast = now;
        notifyAll();
    }

    /**
     * Records something that happened to the monitor, e.g. "paused for the upload".
     */
    public synchronized void event(final String description) {
        append(Kind.EVENT, description);
    }

    /**
     * Records the start of an upload, the point {@code --from upload} refers to.
     */
    public synchronized void markUpload(final String description) {
        Entry entry = append(Kind.EVENT, description);
        uploadPosition = entry.position;
        uploadTime = entry.time;
    }

    /**
     * Records text sent to the board, line ending included.
     */
    public synchronized void sent(final String text) {
        append(Kind.SENT, text);
    }

    /**
     * Drops everything recorded so far, like the monitor's Clear button empties its window.
     */
    public synchronized void clear(final String description) {
        long end = endPosition0();
        entries.clear();
        head = 0;
        firstPosition = end;
        chars = 0;
        openLine = null;
        append(Kind.EVENT, description);
    }

    /**
     * @param description the monitor's window, port and baud rate; null when no monitor is open
     */
    public synchronized void setMonitor(final Map<String, Object> description) {
        if (monitor != null && description == null) ++closures;
        monitor = description == null ? null : new LinkedHashMap<>(description);
        notifyAll();
    }

    /**
     * @return the position the next entry will get
     */
    public synchronized long endPosition() {
        settle(now());
        return endPosition0();
    }

    /**
     * Returns the requested lines, after waiting for them if the query asks to.
     *
     * @return {@code monitor}, {@code lines}, {@code next} (where to continue), {@code more} (whether more lines were
     * requested than returned), {@code stopped} (why a wait ended: match, to, timeout or closed), {@code match},
     * {@code missed} (how many requested lines were dropped already) and {@code to} (the requested end, as a time)
     */
    public Map<String, Object> read(final SerialLogQuery query) throws RemoteException, InterruptedException {
        synchronized (this) {
            long requestTime = now();
            settle(requestTime);
            Range range = new Range(query, requestTime, endPosition0());

            boolean monitorWasOpen = monitor != null;
            long closuresBefore = closures;
            long waitMs = query.timeoutMs != null ? query.timeoutMs : SerialLogQuery.DEFAULT_WAIT_SECONDS * 1000;
            long deadline = requestTime + Math.min(waitMs, SerialLogQuery.MAX_WAIT_SECONDS * 1000);
            boolean waits = query.waitFor != null || query.timeoutMs != null || range.endsLater(requestTime);

            Entry match = null;
            long scanned = range.fromPosition;
            String stopped = null;
            while (true) {
                long now = now();
                settle(now);
                range.update();

                if (query.waitFor != null) {
                    for (long p = Math.max(scanned, range.fromPosition); match == null && p < range.end; ++p) {
                        Entry e = at(p);
                        if (e.kind == Kind.TEXT && query.waitFor.matcher(e.text).find()) match = e;
                    }
                    scanned = Math.max(scanned, range.end);
                }

                if (!waits) break;
                if (match != null) {
                    stopped = "match";
                } else if (range.isComplete(now)) {
                    stopped = "to";
                } else if (monitorWasOpen && closures != closuresBefore) {
                    stopped = "closed";
                } else if (now >= deadline) {
                    stopped = "timeout";
                }
                if (stopped != null) break;

                long until = deadline;
                if (range.toTime != null) until = Math.min(until, range.toTime);
                if (openLine != null) until = Math.min(until, Math.min(openLineLast + QUIET_MS, openLineStart + MAX_LINE_AGE_MS));
                wait(Math.max(1, until - now));
            }
            return result(query, range, waits, match, stopped);
        }
    }

    /**
     * The requested positions; the start is fixed when the call arrives, unless it's a time, and so is the end, unless
     * it's a time that new lines can still fall before.
     */
    private final class Range {
        final SerialLogQuery query;
        final boolean newest; // no start given: the newest lines, or, for a call that waits, what arrives from now on
        final long requested; // the position asked for, before older entries got dropped; -1 for none
        final Long toPosition;
        final Long toTime;
        final Long fromTime;
        long fromPosition;
        long end;

        Range(final SerialLogQuery query, final long requestTime, final long endAtRequest) throws RemoteException {
            this.query = query;
            this.newest = query.from == null;
            SerialLogQuery.Point from = query.from;
            SerialLogQuery.Point to = query.to;

            long requestedPosition = -1;
            Long startTime = null;
            if (from == null) {
                fromPosition = endAtRequest;
            } else {
                switch (from.kind) {
                    case START:
                        fromPosition = firstPosition;
                        break;
                    case NOW:
                        fromPosition = endAtRequest;
                        break;
                    case UPLOAD:
                        if (uploadPosition < 0) {
                            throw new RemoteException(RemoteProtocol.ERR_BAD_REQUEST, "No upload happened since the IDE"
                                    + " started; use --from start, a time or a line number instead");
                        }
                        requestedPosition = uploadPosition;
                        fromPosition = uploadPosition;
                        startTime = uploadTime;
                        break;
                    case POSITION:
                        requestedPosition = Math.min(from.value, endAtRequest);
                        fromPosition = requestedPosition;
                        break;
                    default: // TIME, AGO
                        startTime = from.kind == SerialLogQuery.Point.Kind.TIME ? from.value : requestTime - from.value;
                        fromPosition = positionAtTime(startTime);
                        break;
                }
            }
            this.requested = requestedPosition;
            this.fromTime = startTime;

            Long endPosition = null;
            Long endTime = null;
            if (to != null) {
                switch (to.kind) {
                    case NOW:
                        endPosition = endAtRequest;
                        break;
                    case POSITION:
                        endPosition = to.value;
                        break;
                    case TIME:
                        endTime = to.value;
                        break;
                    case AGO:
                        endTime = requestTime - to.value;
                        break;
                    default: // AFTER
                        endTime = timeOfStart(from, requestTime) + to.value;
                        break;
                }
            }
            this.toPosition = endPosition;
            this.toTime = endTime;
            update();
        }

        void update() {
            if (fromTime != null && query.from != null && query.from.isTimeBased()) fromPosition = positionAtTime(fromTime);
            fromPosition = Math.max(fromPosition, firstPosition);

            end = endPosition0();
            if (toPosition != null) end = Math.min(end, toPosition);
            if (toTime != null) end = Math.min(end, positionAtTime(toTime));
        }

        boolean endsLater(final long now) {
            return (toTime != null && toTime > now) || (toPosition != null && toPosition > endPosition0());
        }

        /**
         * @return whether nothing else can still arrive within the range
         */
        boolean isComplete(final long now) {
            if (toPosition != null && endPosition0() >= toPosition) return true;
            // a line that started before the end time and is still arriving belongs to the range
            return toTime != null && now >= toTime && (openLine == null || openLineStart >= toTime);
        }

        private long timeOfStart(final SerialLogQuery.Point from, final long requestTime) {
            if (fromTime != null) return fromTime;
            switch (from.kind) {
                case START:
                    return count() > 0 ? entries.get(head).time : requestTime;
                case POSITION:
                    long p = Math.max(from.value, firstPosition);
                    return p < endPosition0() ? at(p).time : requestTime;
                default: // NOW
                    return requestTime;
            }
        }
    }

    private Map<String, Object> result(final SerialLogQuery query, final Range range, final boolean waits, final Entry match,
            final String stopped) {

        long end = match != null ? match.position + 1 : range.end;
        long start = range.newest && !waits ? Math.max(firstPosition, end - query.maxLines) : range.fromPosition;

        LocalDate today = Instant.ofEpochMilli(now()).atZone(zone).toLocalDate();
        List<Map<String, Object>> lines = new ArrayList<>();
        long resultChars = 0;
        long position = start;
        while (position < end && lines.size() < query.maxLines) {
            Entry e = at(position);
            if (!lines.isEmpty() && resultChars + e.text.length() > MAX_RESULT_CHARS) break;
            lines.add(describe(e, today));
            resultChars += e.text.length();
            ++position;
        }

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("monitor", monitor == null ? null : new LinkedHashMap<>(monitor));
        if (stopped != null) json.put("stopped", stopped);
        if (match != null) {
            Map<String, Object> matched = describe(match, today);
            matched.put("n", match.position);
            json.put("match", matched);
        }
        json.put("lines", lines);
        json.put("next", position);
        json.put("more", position < end);
        if (range.requested >= 0 && range.requested < firstPosition) json.put("missed", firstPosition - range.requested);
        if (range.toTime != null) json.put("to", format(range.toTime, today));
        return json;
    }

    private Map<String, Object> describe(final Entry e, final LocalDate today) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("t", format(e.time, today));
        line.put(e.kind.key, e.text);
        return line;
    }

    private String format(final long time, final LocalDate today) {
        ZonedDateTime t = Instant.ofEpochMilli(time).atZone(zone);
        return (t.toLocalDate().equals(today) ? TIME_OF_DAY : DATE_AND_TIME).format(t);
    }

    private Entry append(final Kind kind, final String text) {
        long now = now();
        settle(now);
        if (openLine != null) {
            endLine(now); // the entry interrupts the line
            endedEarly = true;
        }
        if (kind == Kind.EVENT) {
            // after e.g. a pause or a reopening, what the monitor receives starts afresh
            endedEarly = false;
            afterCarriageReturn = false;
        }
        Entry entry = add(new Entry(endPosition0(), now, kind, text == null ? "" : text));
        notifyAll();
        return entry;
    }

    /**
     * Ends the line still arriving once it has been quiet, or growing, long enough.
     */
    private void settle(final long now) {
        if (openLine != null && (now - openLineLast >= QUIET_MS || now - openLineStart >= MAX_LINE_AGE_MS)) {
            endLine(now);
            endedEarly = true;
        }
    }

    private void endLine(final long now) {
        String text = openLine == null ? "" : openLine.toString();
        long time = openLine == null ? now : openLineStart;
        openLine = null;
        add(new Entry(endPosition0(), time, Kind.TEXT, text));
    }

    private Entry add(final Entry entry) {
        entries.add(entry);
        chars += entry.text.length();
        while (count() > maxEntries || (chars > maxChars && count() > 1)) {
            chars -= entries.get(head).text.length();
            entries.set(head, null);
            ++head;
            ++firstPosition;
        }
        if (head > 1024 && head > entries.size() / 2) {
            entries.subList(0, head).clear();
            head = 0;
        }
        return entry;
    }

    /**
     * @return the position of the first kept entry at or after the time, or the end
     */
    private long positionAtTime(final long time) {
        int low = head;
        int high = entries.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (entries.get(middle).time < time) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return firstPosition + (low - head);
    }

    private Entry at(final long position) {
        return entries.get(head + (int) (position - firstPosition));
    }

    private int count() {
        return entries.size() - head;
    }

    private long endPosition0() {
        return firstPosition + count();
    }

    /**
     * @return the clock's time, never earlier than a time already handed out
     */
    private long now() {
        long time = Math.max(clock.getAsLong(), lastTime);
        lastTime = time;
        return time;
    }
}
