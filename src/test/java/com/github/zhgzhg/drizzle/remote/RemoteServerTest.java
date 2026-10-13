package com.github.zhgzhg.drizzle.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RemoteServerTest {

    @TempDir
    Path tmp;

    private FakeCommandHandler handler;
    private RemoteServer server;
    private RemoteClient client;
    private InstanceInfo info;

    @BeforeEach
    void startServer() throws Exception {
        handler = new FakeCommandHandler();
        server = RemoteServer.start(handler, "server-test");
        client = new RemoteClient(new InstanceDirectory(tmp));

        info = new InstanceInfo();
        info.instanceId = server.instanceId();
        info.host = server.host();
        info.port = server.port();
        info.token = server.token();
    }

    @AfterEach
    void stopServer() throws Exception {
        handler.release.countDown();
        waitUntilIdle();
        server.stop();
        ActivityGuard.reset();
    }

    private void waitUntilIdle() throws Exception {
        for (int i = 0; i < 100 && ActivityGuard.busyDescription() != null; ++i) {
            Thread.sleep(50);
        }
    }

    private static Map<String, String> sketchArgs(final String sketch) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put(RemoteProtocol.ARG_SKETCH, sketch);
        return args;
    }

    @Test
    void listensOnLoopbackOnly() {
        assertTrue(InetAddress.getLoopbackAddress().getHostAddress().equals(server.host()));
    }

    @Test
    void statusDescribesTheInstance() throws Exception {
        Map<String, Object> status = client.status(info);
        assertEquals(true, status.get("ok"));
        assertEquals("server-test", status.get("instance"));
        assertEquals(1L, status.get("api"));
        assertEquals("1.8.19", status.get("ide"));
        assertEquals(false, status.get("busy"));
    }

    @Test
    void rejectsWrongOrMissingTokens() throws Exception {
        info.token = "wrong";
        assertRemoteError(RemoteProtocol.ERR_UNAUTHORIZED, () -> client.status(info));

        String response = rawRequest("GET " + RemoteProtocol.STATUS_PATH + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + server.port() + "\r\n");
        assertTrue(response.startsWith("HTTP/1.1 401"), response);
    }

    @Test
    void rejectsRequestsFromWebPages() throws Exception {
        String withToken = RemoteProtocol.TOKEN_HEADER + ": " + server.token() + "\r\n";

        String fromPage = rawRequest("GET " + RemoteProtocol.STATUS_PATH + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + server.port() + "\r\n" + withToken + "Origin: https://example.com\r\n");
        assertTrue(fromPage.startsWith("HTTP/1.1 403"), fromPage);

        String dnsRebinding = rawRequest("GET " + RemoteProtocol.STATUS_PATH + " HTTP/1.1\r\n"
                + "Host: attacker.example:" + server.port() + "\r\n" + withToken);
        assertTrue(dnsRebinding.startsWith("HTTP/1.1 403"), dnsRebinding);

        String ok = rawRequest("GET " + RemoteProtocol.STATUS_PATH + " HTTP/1.1\r\n"
                + "Host: localhost:" + server.port() + "\r\n" + withToken);
        assertTrue(ok.startsWith("HTTP/1.1 200"), ok);
    }

    @Test
    void jobsRequireJson() throws Exception {
        String response = rawRequest("POST " + RemoteProtocol.JOBS_PATH + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + server.port() + "\r\n"
                + RemoteProtocol.TOKEN_HEADER + ": " + server.token() + "\r\n"
                + "Content-Type: text/plain\r\nContent-Length: 2\r\n\r\n{}");
        assertTrue(response.startsWith("HTTP/1.1 400"), response);
    }

    @Test
    void runsJobsAndReportsTheirOutput() throws Exception {
        Map<String, Object> job = client.submit(info, RemoteProtocol.CMD_COMPILE, sketchArgs("/sketches/blink"));
        Map<String, Object> finished = client.waitFor(info, (String) job.get("id"), 10000, null);

        assertEquals(RemoteProtocol.JOB_DONE, finished.get("state"));
        assertEquals(Collections.singletonMap("ok", true), finished.get("result"));
        assertEquals("compile started\ncompile finished\n", finished.get("log"));
        assertEquals("/sketches/blink", handler.lastArgs.get(RemoteProtocol.ARG_SKETCH));
    }

    @Test
    void reportsFailuresWithTheirCode() throws Exception {
        Map<String, Object> job = client.submit(info, RemoteProtocol.CMD_COMPILE, sketchArgs("/sketches/unsaved"));
        Map<String, Object> finished = client.waitFor(info, (String) job.get("id"), 10000, null);

        assertEquals(RemoteProtocol.JOB_FAILED, finished.get("state"));
        assertEquals(RemoteProtocol.ERR_UNSAVED_CHANGES, ((Map<?, ?>) finished.get("error")).get("code"));
    }

    @Test
    void rejectsUnknownCommands() {
        assertRemoteError(RemoteProtocol.ERR_BAD_REQUEST, () -> client.submit(info, "format-disk", sketchArgs("/x")));
    }

    @Test
    void runsOneJobAtATime() throws Exception {
        Map<String, Object> slow = client.submit(info, RemoteProtocol.CMD_COMPILE, sketchArgs("/sketches/slow"));
        assertRemoteError(RemoteProtocol.ERR_BUSY, () -> client.submit(info, RemoteProtocol.CMD_COMPILE, sketchArgs("/sketches/blink")));

        Map<String, Object> status = client.status(info);
        assertEquals(true, status.get("busy"));
        assertEquals(slow.get("id"), ((Map<?, ?>) status.get("currentJob")).get("id"));

        handler.release.countDown();
        assertEquals(RemoteProtocol.JOB_DONE, client.waitFor(info, (String) slow.get("id"), 10000, null).get("state"));
    }

    @Test
    void waitsForActionsStartedInTheIDE() throws Exception {
        try (ActivityGuard.Lease lease = ActivityGuard.tryLocal("Apply Markers")) {
            assertRemoteError(RemoteProtocol.ERR_BUSY, () -> client.submit(info, RemoteProtocol.CMD_COMPILE, sketchArgs("/s/blink")));

            // quick commands that don't interfere with the IDE's own actions still run
            Map<String, Object> job = client.submit(info, RemoteProtocol.CMD_LIST_FQBNS, Collections.<String, String>emptyMap());
            assertEquals(RemoteProtocol.JOB_DONE, client.waitFor(info, (String) job.get("id"), 10000, null).get("state"));
            assertTrue(lease.isHeld());
        }
    }

    @Test
    void cancelsJobsThroughTheApi() throws Exception {
        Map<String, Object> slow = client.submit(info, RemoteProtocol.CMD_COMPILE, sketchArgs("/sketches/slow"));
        assertTrue(handler.started.await(10, TimeUnit.SECONDS));

        Map<String, Object> cancelled = client.cancel(info, (String) slow.get("id"));
        assertEquals(RemoteProtocol.JOB_FAILED, cancelled.get("state"));
        assertEquals(RemoteProtocol.ERR_CANCELLED, ((Map<?, ?>) cancelled.get("error")).get("code"));
        assertEquals(false, client.status(info).get("busy"));

        Map<String, Object> next = client.submit(info, RemoteProtocol.CMD_COMPILE, sketchArgs("/sketches/blink"));
        assertEquals(RemoteProtocol.JOB_DONE, client.waitFor(info, (String) next.get("id"), 10000, null).get("state"));
        assertEquals(RemoteProtocol.JOB_FAILED, client.job(info, (String) slow.get("id"), 0).get("state"),
                "a cancelled job stays cancelled when its thread returns");
    }

    @Test
    void reportsACancelFromTheIdeEvenWhenTheJobEndsRightAway() throws Exception {
        Map<String, Object> slow = client.submit(info, RemoteProtocol.CMD_COMPILE, sketchArgs("/sketches/slow"));
        assertTrue(handler.started.await(10, TimeUnit.SECONDS));

        // the interrupt makes the command return at once, like a compile that fails when interrupted
        ActivityGuard.cancelRemote();
        Thread.sleep(300); // lets the job finish before anyone asks about it

        Map<String, Object> finished = client.waitFor(info, (String) slow.get("id"), 10000, null);
        assertEquals(RemoteProtocol.JOB_FAILED, finished.get("state"));
        assertEquals(RemoteProtocol.ERR_CANCELLED, ((Map<?, ?>) finished.get("error")).get("code"));
    }

    @Test
    void cancelFromTheIdeFreesItEvenWhenTheJobIsStuck() throws Exception {
        Map<String, Object> stuck = client.submit(info, RemoteProtocol.CMD_APPLY_MARKERS, sketchArgs("/sketches/stuck"));
        assertTrue(handler.started.await(10, TimeUnit.SECONDS));
        assertEquals("remote apply-markers (job " + stuck.get("id") + ")", ActivityGuard.busyDescription());

        // what Tools > Drizzle > Cancel Remote Command does
        assertTrue(ActivityGuard.cancelRemote().startsWith("remote apply-markers"));
        try (ActivityGuard.Lease verify = ActivityGuard.tryLocal("Verify/Upload")) {
            assertNotNull(verify, "Verify works again although the job's thread is still blocked");
        }

        Map<String, Object> status = client.status(info);
        assertEquals(false, status.get("busy"));
        assertEquals(RemoteProtocol.JOB_FAILED, ((Map<?, ?>) status.get("lastJob")).get("state"));

        Map<String, Object> next = client.submit(info, RemoteProtocol.CMD_COMPILE, sketchArgs("/sketches/blink"));
        assertEquals(RemoteProtocol.JOB_DONE, client.waitFor(info, (String) next.get("id"), 10000, null).get("state"),
                "later jobs get a fresh thread");
    }

    @Test
    void returnsOnlyTheNewOutputWhenAsked() throws Exception {
        Map<String, Object> job = client.submit(info, RemoteProtocol.CMD_COMPILE, sketchArgs("/sketches/blink"));
        client.waitFor(info, (String) job.get("id"), 10000, null);

        Map<String, Object> tail = client.job(info, (String) job.get("id"), "compile started\n".length());
        assertEquals("compile finished\n", tail.get("log"));
    }

    private interface RemoteCall {
        void run() throws Exception;
    }

    private static void assertRemoteError(final String expectedCode, final RemoteCall call) {
        try {
            call.run();
            fail("expected " + expectedCode);
        } catch (RemoteException e) {
            assertEquals(expectedCode, e.getCode(), e.getMessage());
        } catch (Exception e) {
            fail(e);
        }
    }

    private String rawRequest(final String headLines) throws Exception {
        String request = headLines.contains("\r\n\r\n") ? headLines : headLines + "Connection: close\r\n\r\n";
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), server.port())) {
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            return in.readLine();
        }
    }
}
