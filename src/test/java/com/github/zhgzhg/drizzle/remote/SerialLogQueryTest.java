package com.github.zhgzhg.drizzle.remote;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SerialLogQueryTest {
    private static final ZoneId UTC = ZoneOffset.UTC;
    private static final long NOON = Instant.parse("2026-11-09T12:00:00Z").toEpochMilli();

    private static SerialLogQuery parse(final String... optionsAndValues) throws RemoteException {
        Map<String, String> args = new LinkedHashMap<>();
        for (int i = 0; i < optionsAndValues.length; i += 2) {
            args.put(optionsAndValues[i], optionsAndValues[i + 1]);
        }
        return SerialLogQuery.fromArgs(args, NOON, UTC);
    }

    private static SerialLogQuery.Point from(final String value) throws RemoteException {
        return parse(RemoteProtocol.ARG_FROM, value).from;
    }

    private static SerialLogQuery.Point to(final String value) throws RemoteException {
        return parse(RemoteProtocol.ARG_FROM, "start", RemoteProtocol.ARG_TO, value).to;
    }

    private static void assertRejected(final String... optionsAndValues) {
        RemoteException e = assertThrows(RemoteException.class, () -> parse(optionsAndValues));
        assertEquals(RemoteProtocol.ERR_BAD_REQUEST, e.getCode());
    }

    @Test
    void understandsTheNamedPointsAndLineNumbers() throws Exception {
        assertEquals(SerialLogQuery.Point.Kind.START, from("start").kind);
        assertEquals(SerialLogQuery.Point.Kind.UPLOAD, from("Upload").kind);
        assertEquals(SerialLogQuery.Point.Kind.NOW, from("now").kind);
        assertEquals(SerialLogQuery.Point.Kind.NOW, to("now").kind);

        SerialLogQuery.Point line = from("42");
        assertEquals(SerialLogQuery.Point.Kind.POSITION, line.kind);
        assertEquals(42, line.value);
    }

    @Test
    void understandsDurations() throws Exception {
        SerialLogQuery.Point ago = from("-30s");
        assertEquals(SerialLogQuery.Point.Kind.AGO, ago.kind);
        assertEquals(30_000, ago.value);
        assertEquals(500, from("-500ms").value);
        assertEquals(90_000, from("-1.5m").value);
        assertEquals(120_000, from("-2min").value);
        assertEquals(3600_000, from("-1h").value);

        SerialLogQuery.Point after = to("+10s");
        assertEquals(SerialLogQuery.Point.Kind.AFTER, after.kind);
        assertEquals(10_000, after.value);
    }

    @Test
    void placesTimesOfDayOnTheRightDay() throws Exception {
        SerialLogQuery.Point earlier = from("11:58:30.25");
        assertEquals(SerialLogQuery.Point.Kind.TIME, earlier.kind);
        assertEquals(Instant.parse("2026-11-09T11:58:30.250Z").toEpochMilli(), earlier.value);

        assertEquals(Instant.parse("2026-11-09T12:05:00Z").toEpochMilli(), to("12:05").value, "a little ahead is today");
        long oneAm = Instant.parse("2026-11-09T01:00:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-11-08T23:59:00Z").toEpochMilli(),
                SerialLogQuery.fromArgs(Collections.singletonMap(RemoteProtocol.ARG_FROM, "23:59"), oneAm, UTC).from.value,
                "far ahead is yesterday");

        assertEquals(Instant.parse("2026-11-08T23:59:59.123Z").toEpochMilli(), from("2026-11-08 23:59:59.123").value);
        assertEquals(Instant.parse("2026-11-08T23:59:00Z").toEpochMilli(), from("2026-11-08T23:59").value);
    }

    @Test
    void hasDefaults() throws Exception {
        SerialLogQuery query = parse();
        assertNull(query.from);
        assertNull(query.to);
        assertNull(query.waitFor);
        assertNull(query.timeoutMs);
        assertEquals(SerialLogQuery.DEFAULT_MAX_LINES, query.maxLines);

        assertEquals(5000L, parse(RemoteProtocol.ARG_TIMEOUT, "5").timeoutMs);
        assertEquals(10, parse(RemoteProtocol.ARG_MAX_LINES, "10").maxLines);
    }

    @Test
    void rejectsWhatItDoesntUnderstand() {
        assertRejected(RemoteProtocol.ARG_FROM, "yesterday");
        assertRejected(RemoteProtocol.ARG_FROM, "-30");
        assertRejected(RemoteProtocol.ARG_FROM, "+10s");
        assertRejected(RemoteProtocol.ARG_FROM, "25:00");
        assertRejected(RemoteProtocol.ARG_FROM, "99999999999999999999");
        assertRejected(RemoteProtocol.ARG_FROM, "start", RemoteProtocol.ARG_TO, "start");
        assertRejected(RemoteProtocol.ARG_FROM, "start", RemoteProtocol.ARG_TO, "upload");
        assertRejected(RemoteProtocol.ARG_TO, "+10s");
        assertRejected(RemoteProtocol.ARG_WAIT_FOR_REGEX, "(");
        assertRejected(RemoteProtocol.ARG_WAIT_FOR_REGEX, "");
        assertRejected(RemoteProtocol.ARG_TIMEOUT, "601");
        assertRejected(RemoteProtocol.ARG_TIMEOUT, "-1");
        assertRejected(RemoteProtocol.ARG_TIMEOUT, "soon");
        assertRejected(RemoteProtocol.ARG_MAX_LINES, "0");
        assertRejected(RemoteProtocol.ARG_MAX_LINES, "2001");
    }

    @Test
    void tellsHowLongACallMayWait() {
        assertEquals(0, SerialLogQuery.maxWaitSeconds(Collections.emptyMap()));
        assertEquals(0, SerialLogQuery.maxWaitSeconds(Collections.singletonMap(RemoteProtocol.ARG_FROM, "start")));
        assertEquals(SerialLogQuery.DEFAULT_WAIT_SECONDS,
                SerialLogQuery.maxWaitSeconds(Collections.singletonMap(RemoteProtocol.ARG_WAIT_FOR_REGEX, "OK")));
        assertEquals(SerialLogQuery.DEFAULT_WAIT_SECONDS, SerialLogQuery.maxWaitSeconds(Collections.singletonMap(RemoteProtocol.ARG_TO, "+5s")));
        assertEquals(12, SerialLogQuery.maxWaitSeconds(Collections.singletonMap(RemoteProtocol.ARG_TIMEOUT, "12")));
    }
}
