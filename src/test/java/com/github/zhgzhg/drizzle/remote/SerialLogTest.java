package com.github.zhgzhg.drizzle.remote;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialLogTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long NOON = Instant.parse("2026-11-09T12:00:00Z").toEpochMilli();

    private final AtomicLong clock = new AtomicLong(NOON);
    private SerialLog log = new SerialLog(clock::get, UTC, 1000, 100_000);

    private Map<String, Object> read(final String... optionsAndValues) throws Exception {
        return read(log, clock.get(), optionsAndValues);
    }

    private static Map<String, Object> read(final SerialLog log, final long now, final String... optionsAndValues)
            throws Exception {
        Map<String, String> args = new LinkedHashMap<>();
        for (int i = 0; i < optionsAndValues.length; i += 2) {
            args.put(optionsAndValues[i], optionsAndValues[i + 1]);
        }
        return log.read(SerialLogQuery.fromArgs(args, now, log.zone()));
    }

    /**
     * @return the lines as "text", "event: ..." or "sent: ..."
     */
    @SuppressWarnings("unchecked")
    private static List<String> lines(final Map<String, Object> result) {
        List<String> lines = new ArrayList<>();
        for (Map<String, Object> line : (List<Map<String, Object>>) result.get("lines")) {
            if (line.containsKey("text")) lines.add((String) line.get("text"));
            if (line.containsKey("event")) lines.add("event: " + line.get("event"));
            if (line.containsKey("sent")) lines.add("sent: " + line.get("sent"));
        }
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static List<String> times(final Map<String, Object> result) {
        List<String> times = new ArrayList<>();
        for (Map<String, Object> line : (List<Map<String, Object>>) result.get("lines")) {
            times.add((String) line.get("t"));
        }
        return times;
    }

    private void tick(final long ms) {
        clock.addAndGet(ms);
    }

    @Test
    void splitsLinesAtAnyLineBreakEvenWhenItArrivesInPieces() throws Exception {
        log.received("one\r\ntwo\nthree\rfour\r");
        log.received("\nfive\n\n");

        assertEquals(Arrays.asList("one", "two", "three", "four", "five", ""), lines(read("from", "start")));
    }

    @Test
    void handsOutALineWithoutLineBreakOnceNothingGetsAddedToIt() throws Exception {
        log.received("Temp=");
        tick(100);
        log.received("21.5\nEnter a command: ");
        assertEquals(Collections.singletonList("Temp=21.5"), lines(read("from", "start")), "the prompt may still grow");

        tick(SerialLog.QUIET_MS);
        assertEquals(Arrays.asList("Temp=21.5", "Enter a command: "), lines(read("from", "start")));

        log.received("help\n");
        assertEquals(Arrays.asList("Temp=21.5", "Enter a command: ", "help"), lines(read("from", "start")),
                "a line handed out never changes");
    }

    @Test
    void aLineBreakArrivingLateEndsTheLineAlreadyHandedOut() throws Exception {
        log.received("Connecting...");
        tick(SerialLog.QUIET_MS);
        log.received("\r");
        log.received("\nconnected\n\n");
        assertEquals(Arrays.asList("Connecting...", "connected", ""), lines(read("from", "start")),
                "no empty line for the late line break; the blank line the board printed stays");

        char[] longLine = new char[SerialLog.MAX_LINE_CHARS];
        Arrays.fill(longLine, 'x');
        log.received(new String(longLine) + "\n");
        assertEquals(SerialLog.MAX_LINE_CHARS, lines(read("from", "3")).get(0).length());
        assertEquals(1, lines(read("from", "3")).size());

        log.received("> ");
        log.event("paused for the upload");
        log.received("\nrestarted\n");
        assertEquals(Arrays.asList("> ", "event: paused for the upload", "", "restarted"), lines(read("from", "4")),
                "after a pause the text starts afresh");
    }

    @Test
    void splitsLinesThatKeepGrowing() throws Exception {
        for (int i = 0; i < 25; ++i) {
            log.received(".");
            tick(100);
        }
        tick(SerialLog.QUIET_MS);
        assertEquals(Arrays.asList("....................", "....."), lines(read("from", "start")));

        char[] longLine = new char[SerialLog.MAX_LINE_CHARS + 10];
        Arrays.fill(longLine, 'x');
        log.received(new String(longLine) + "\n");
        List<String> lines = lines(read("from", "2"));
        assertEquals(SerialLog.MAX_LINE_CHARS, lines.get(0).length());
        assertEquals(10, lines.get(1).length());
    }

    @Test
    void eventsAndSentTextEndTheLineInProgress() throws Exception {
        log.received("> ");
        log.sent("help\n");
        log.received("commands: on, off\n");
        log.event("closed in the IDE: Serial Monitor");

        assertEquals(Arrays.asList("> ", "sent: help\n", "commands: on, off", "event: closed in the IDE: Serial Monitor"),
                lines(read("from", "start")));
    }

    @Test
    void continuesWhereTheLastCallStopped() throws Exception {
        log.received("1\n2\n3\n4\n5\n");

        Map<String, Object> first = read("from", "start", "maxLines", "2");
        assertEquals(Arrays.asList("1", "2"), lines(first));
        assertEquals(true, first.get("more"));
        assertEquals(2L, first.get("next"));

        Map<String, Object> rest = read("from", String.valueOf(first.get("next")));
        assertEquals(Arrays.asList("3", "4", "5"), lines(rest));
        assertEquals(false, rest.get("more"));
        assertEquals(5L, rest.get("next"));

        assertEquals(Collections.emptyList(), lines(read("from", "5")));
    }

    @Test
    void returnsTheNewestLinesByDefault() throws Exception {
        for (int i = 0; i < 10; ++i) log.received("line " + i + "\n");

        Map<String, Object> newest = read("maxLines", "3");
        assertEquals(Arrays.asList("line 7", "line 8", "line 9"), lines(newest));
        assertEquals(10L, newest.get("next"));
        assertNull(newest.get("stopped"), "nothing to wait for");
    }

    @Test
    void selectsLinesByTime() throws Exception {
        log.received("a\n");
        tick(1000);
        log.received("b\n");
        tick(1000);
        log.received("c\n");

        assertEquals(Arrays.asList("12:00:00.000", "12:00:01.000", "12:00:02.000"), times(read("from", "start")));
        assertEquals(Arrays.asList("b", "c"), lines(read("from", "12:00:01")));
        assertEquals(Arrays.asList("b", "c"), lines(read("from", "-1500ms")));
        assertEquals(Arrays.asList("a", "b"), lines(read("from", "start", "to", "12:00:02.000")));
        assertEquals(Arrays.asList("a", "b"), lines(read("to", "-500ms")));
        assertEquals(Collections.singletonList("a"), lines(read("from", "start", "to", "+1s")));
        assertEquals(Collections.singletonList("b"), lines(read("from", "1", "to", "2")));
    }

    @Test
    void showsTheDateOfLinesFromEarlierDays() throws Exception {
        clock.set(NOON - 24 * 3600_000L);
        log.received("yesterday\n");
        clock.set(NOON);
        log.received("today\n");

        assertEquals(Arrays.asList("2026-11-08 12:00:00.000", "12:00:00.000"), times(read("from", "start")));
        assertEquals(Collections.singletonList("yesterday"), lines(read("from", "2026-11-08 12:00:00", "to", "2026-11-08T13:00")));
    }

    @Test
    void readsWhatCameAfterTheLastUpload() throws Exception {
        RemoteException noUpload = assertThrows(RemoteException.class, () -> read("from", "upload"));
        assertEquals(RemoteProtocol.ERR_BAD_REQUEST, noUpload.getCode());

        log.received("old program\n");
        log.markUpload("upload started");
        log.event("upload finished");
        log.received("new program\n");

        assertEquals(Arrays.asList("event: upload started", "event: upload finished", "new program"),
                lines(read("from", "upload")));
    }

    @Test
    void clearingDropsEverythingAndTellsWhatGotMissed() throws Exception {
        log.received("a\nb\n");
        log.clear("cleared in the IDE");
        log.received("c\n");

        Map<String, Object> result = read("from", "0");
        assertEquals(Arrays.asList("event: cleared in the IDE", "c"), lines(result));
        assertEquals(2L, result.get("missed"));
        assertEquals(Arrays.asList("event: cleared in the IDE", "c"), lines(read("from", "start")));
    }

    @Test
    void dropsTheOldestEntriesBeyondTheLimit() throws Exception {
        log = new SerialLog(clock::get, UTC, 3, 100_000);
        log.received("1\n2\n3\n4\n5\n");
        assertEquals(Arrays.asList("3", "4", "5"), lines(read("from", "start")));
        assertEquals(2L, read("from", "0").get("missed"));

        log = new SerialLog(clock::get, UTC, 100, 10);
        log.received("12345\n67890\nabcde\n");
        assertEquals(Arrays.asList("67890", "abcde"), lines(read("from", "start")));
    }

    @Test
    void reportsTheMonitor() throws Exception {
        Map<String, Object> monitor = new LinkedHashMap<>();
        monitor.put("window", "Serial Monitor");
        monitor.put("port", "COM5");
        log.setMonitor(monitor);
        assertEquals(monitor, read().get("monitor"));

        log.setMonitor(null);
        assertNull(read().get("monitor"));
    }

    // the calls below wait, so they run on the real clock

    @Test
    void waitsForAMatchingLine() throws Exception {
        SerialLog live = new SerialLog();
        live.received("READY from the last run\n");
        Thread board = later(200, () -> live.received("booting\nREADY\nmore\n"));

        long started = System.currentTimeMillis();
        Map<String, Object> result = read(live, System.currentTimeMillis(), "waitForRegex", "^READY$", "timeout", "10");
        board.join();

        assertEquals("match", result.get("stopped"));
        assertEquals(Arrays.asList("booting", "READY"), lines(result), "from the call on, up to the match");
        assertEquals("READY", ((Map<?, ?>) result.get("match")).get("text"));
        assertEquals(2L, ((Map<?, ?>) result.get("match")).get("n"));
        assertEquals(3L, result.get("next"));
        assertTrue(System.currentTimeMillis() - started < 5000);

        Map<String, Object> earlier = read(live, System.currentTimeMillis(), "from", "start", "waitForRegex", "READY");
        assertEquals("match", earlier.get("stopped"), "lines that arrived before the call count when asked for");
        assertEquals(0L, ((Map<?, ?>) earlier.get("match")).get("n"));
    }

    @Test
    void collectsWhatArrivesUntilTheTimeout() throws Exception {
        SerialLog live = new SerialLog();
        Thread board = later(100, () -> live.received("tick\n"));

        long started = System.currentTimeMillis();
        Map<String, Object> result = read(live, System.currentTimeMillis(), "timeout", "1");
        board.join();

        assertEquals("timeout", result.get("stopped"));
        assertEquals(Collections.singletonList("tick"), lines(result));
        assertTrue(System.currentTimeMillis() - started >= 900);

        Map<String, Object> never = read(live, System.currentTimeMillis(), "waitForRegex", "never", "timeout", "0");
        assertEquals("timeout", never.get("stopped"));
    }

    @Test
    void waitsUntilTheEndOfTheRequestedTime() throws Exception {
        SerialLog live = new SerialLog();
        Thread board = later(100, () -> live.received("early\n"));

        long started = System.currentTimeMillis();
        Map<String, Object> result = read(live, System.currentTimeMillis(), "from", "now", "to", "+600ms");
        board.join();

        assertEquals("to", result.get("stopped"));
        assertEquals(Collections.singletonList("early"), lines(result));
        assertTrue(System.currentTimeMillis() - started >= 500);
        assertTrue(result.containsKey("to"));
    }

    @Test
    void stopsWaitingWhenTheMonitorGetsClosed() throws Exception {
        SerialLog live = new SerialLog();
        live.setMonitor(Collections.singletonMap("window", "Serial Monitor"));
        Thread user = later(200, () -> {
            live.event("closed in the IDE: Serial Monitor");
            live.setMonitor(null);
        });

        long started = System.currentTimeMillis();
        Map<String, Object> result = read(live, System.currentTimeMillis(), "waitForRegex", "never", "timeout", "10");
        user.join();

        assertEquals("closed", result.get("stopped"));
        assertEquals(Collections.singletonList("event: closed in the IDE: Serial Monitor"), lines(result));
        assertTrue(System.currentTimeMillis() - started < 5000);
        assertFalse(result.containsKey("match"));
    }

    private static Thread later(final long delayMs, final Runnable action) {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                return;
            }
            action.run();
        });
        thread.start();
        return thread;
    }
}
