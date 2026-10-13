package com.github.zhgzhg.drizzle.remote;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteCLITest {

    @TempDir
    Path tmp;

    private InstanceDirectory directory;
    private final List<RemoteServer> servers = new ArrayList<>();
    private final List<FakeCommandHandler> handlers = new ArrayList<>();
    private final List<HttpServer> otherServers = new ArrayList<>();

    private File blink;
    private File broken;
    private File unsaved;
    private File slow;
    private File closed;

    private Map<String, Object> lastOutput;
    private String lastRawOutput;
    private String lastErr;

    @BeforeEach
    void createSketches() throws Exception {
        directory = new InstanceDirectory(tmp.resolve("instances"));
        blink = sketch("blink");
        broken = sketch("broken");
        unsaved = sketch("unsaved");
        slow = sketch("slow");
        closed = sketch("closed");
    }

    @AfterEach
    void stopServers() throws Exception {
        handlers.forEach(h -> h.release.countDown());
        for (int i = 0; i < 100 && ActivityGuard.busyDescription() != null; ++i) Thread.sleep(50);
        servers.forEach(RemoteServer::stop);
        otherServers.forEach(s -> s.stop(0));
        ActivityGuard.reset();
    }

    private File sketch(final String name) throws Exception {
        File folder = tmp.resolve("sketches").resolve(name).toFile();
        assertTrue(folder.mkdirs());
        Files.write(new File(folder, name + ".ino").toPath(), "void setup() {}\nvoid loop() {}\n".getBytes(StandardCharsets.UTF_8));
        return folder.getCanonicalFile();
    }

    private RemoteServer startInstance(final long pid, final File... openSketches) throws Exception {
        FakeCommandHandler handler = new FakeCommandHandler(openSketches);
        RemoteServer server = RemoteServer.start(handler, "instance-" + pid);
        handlers.add(handler);
        servers.add(server);

        InstanceInfo info = new InstanceInfo();
        info.instanceId = server.instanceId();
        info.pid = pid;
        info.host = server.host();
        info.port = server.port();
        info.token = server.token();
        for (File sketch : openSketches) {
            info.sketches.add(new File(sketch, sketch.getName() + ".ino").getPath());
        }
        directory.write(info);
        return server;
    }

    private int cli(final String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream o = new PrintStream(out, true); PrintStream e = new PrintStream(err, true)) {
            exitCode = new RemoteCLI(new RemoteClient(directory), o, e).execute(args);
        }
        lastErr = new String(err.toByteArray(), StandardCharsets.UTF_8);
        lastRawOutput = new String(out.toByteArray(), StandardCharsets.UTF_8);
        lastOutput = new Gson().fromJson(lastRawOutput, new TypeToken<Map<String, Object>>() { }.getType());
        return exitCode;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputMap(final String key) {
        return (Map<String, Object>) lastOutput.get(key);
    }

    @Test
    void reportsWhenNoIdeRuns() {
        assertEquals(RemoteCLI.EXIT_NO_INSTANCE, cli("list"));
        assertEquals(false, lastOutput.get("ok"));
        assertEquals(RemoteCLI.EXIT_NO_INSTANCE, cli("compile", "--sketch", blink.getPath()));
    }

    @Test
    void cancelsJobs() throws Exception {
        startInstance(1, slow);

        assertEquals(RemoteCLI.EXIT_OK, cli("apply-markers", "--sketch", slow.getPath(), "--no-wait"));
        String jobId = (String) lastOutput.get("job");
        assertTrue(handlers.get(0).started.await(10, TimeUnit.SECONDS));

        assertEquals(RemoteCLI.EXIT_OK, cli("cancel", jobId));
        assertEquals(true, lastOutput.get("ok"));
        assertEquals(RemoteProtocol.ERR_CANCELLED, outputMap("error").get("code"));

        assertEquals(RemoteCLI.EXIT_FAILED, cli("job", jobId), "a cancelled job counts as failed for whoever waits for it");
        assertEquals(RemoteCLI.EXIT_USAGE, cli("cancel"));
    }

    @Test
    void reportsTheIdeShowingTheSketchAsUnresponsiveRatherThanTheSketchAsClosed() throws Exception {
        startInstance(1, blink);
        handlers.get(0).uiBlocked = true;
        startInstance(2, broken);

        assertEquals(RemoteCLI.EXIT_COMMUNICATION, cli("compile", "--sketch", blink.getPath()));
        assertEquals(RemoteProtocol.ERR_IDE_UNRESPONSIVE, outputMap("error").get("code"));

        assertEquals(RemoteCLI.EXIT_COMMUNICATION, cli("open", "--sketch", blink.getPath()));
        assertFalse(handlers.get(1).openSketchFolders.contains(blink), "no second window on the same sketch");
    }

    @Test
    void deletesInstanceFilesWhosePortAnotherProgramTookOver() throws Exception {
        HttpServer other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        other.createContext("/", exchange -> {
            byte[] page = "<html>not Drizzle</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, page.length);
            exchange.getResponseBody().write(page);
            exchange.close();
        });
        other.start();
        otherServers.add(other);

        InstanceInfo gone = InstanceDirectoryTest.sampleInfo(99);
        gone.port = other.getAddress().getPort();
        Path goneFile = directory.write(gone);
        startInstance(1, blink);

        assertEquals(RemoteCLI.EXIT_OK, cli("list"));
        assertEquals(1, ((List<?>) lastOutput.get("instances")).size());
        assertFalse(Files.exists(goneFile));
    }

    @Test
    void printsJsonInAsciiSoNoConsoleCodePageCanMangleIt() throws Exception {
        File cyrillic;
        try {
            cyrillic = sketch("блинк");
        } catch (InvalidPathException e) {
            Assumptions.assumeTrue(false, "the file system can't hold non-ASCII names here");
            return;
        }
        startInstance(1, cyrillic);

        assertEquals(RemoteCLI.EXIT_OK, cli("status", "--sketch", cyrillic.getPath()));
        assertTrue(lastRawOutput.chars().allMatch(c -> c < 0x80), lastRawOutput);
        assertTrue(lastRawOutput.contains("\\u0431\\u043b"), lastRawOutput);
        assertEquals(cyrillic.getPath(), outputMap("sketch").get("folder"));
    }

    @Test
    void rejectsUnknownInstanceIds() throws Exception {
        startInstance(1, blink);

        assertEquals(RemoteCLI.EXIT_USAGE, cli("compile", "--sketch", blink.getPath(), "--instance", "gone"));
        assertTrue(String.valueOf(outputMap("error").get("message")).contains("instance-1"));
    }

    @Test
    void tellsApartIdesThatDontRespondFromMissingOnes() throws Exception {
        RemoteServer server = startInstance(1, blink);
        handlers.get(0).uiBlocked = true;

        assertEquals(RemoteCLI.EXIT_COMMUNICATION, cli("compile", "--sketch", blink.getPath()));
        assertEquals(RemoteProtocol.ERR_IDE_UNRESPONSIVE, outputMap("error").get("code"));

        assertEquals(RemoteCLI.EXIT_COMMUNICATION, cli("list"));
        Map<?, ?> listed = (Map<?, ?>) ((List<?>) lastOutput.get("instances")).get(0);
        assertEquals(server.instanceId(), listed.get("instance"));
        assertEquals(false, listed.get("responding"));
        assertTrue(Files.exists(directory.fileOf(1)), "a busy IDE's file must stay");
    }

    @Test
    void deletesInstanceFilesOfIdesThatAreGone() throws Exception {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            unusedPort = socket.getLocalPort();
        }
        InstanceInfo gone = InstanceDirectoryTest.sampleInfo(99);
        gone.port = unusedPort;
        Path goneFile = directory.write(gone);

        startInstance(1, blink);

        assertEquals(RemoteCLI.EXIT_OK, cli("list"));
        assertEquals(1, ((List<?>) lastOutput.get("instances")).size());
        assertFalse(Files.exists(goneFile));
    }

    @Test
    void sendsCommandsToTheIdeShowingTheSketch() throws Exception {
        startInstance(1, broken);
        RemoteServer showingBlink = startInstance(2, blink);

        // a file inside the sketch folder identifies the sketch too
        assertEquals(RemoteCLI.EXIT_OK, cli("compile", "--sketch", new File(blink, "blink.ino").getPath(), "--verbose"));
        assertEquals(true, lastOutput.get("ok"));
        assertEquals(showingBlink.instanceId(), lastOutput.get("instance"));
        assertEquals("compile", lastOutput.get("command"));
        assertEquals("compile started\ncompile finished\n", lastOutput.get("log"));
        assertEquals("true", handlers.get(1).lastArgs.get(RemoteProtocol.ARG_VERBOSE));
        assertTrue(lastErr.contains("started as job"), lastErr);
    }

    @Test
    void reportsCompilationErrors() throws Exception {
        startInstance(1, broken);

        assertEquals(RemoteCLI.EXIT_FAILED, cli("compile", "--sketch", broken.getPath()));
        assertEquals(false, lastOutput.get("ok"));
        List<?> diagnostics = (List<?>) outputMap("result").get("diagnostics");
        assertEquals(1, diagnostics.size());
        assertEquals("'x' was not declared in this scope", ((Map<?, ?>) diagnostics.get(0)).get("message"));
    }

    @Test
    void mapsFailuresToExitCodes() throws Exception {
        startInstance(1, blink, unsaved);

        assertEquals(RemoteCLI.EXIT_UNSAVED_CHANGES, cli("apply-markers", "--sketch", unsaved.getPath()));
        assertEquals(RemoteProtocol.ERR_UNSAVED_CHANGES, outputMap("error").get("code"));

        assertEquals(RemoteCLI.EXIT_SKETCH_NOT_OPEN, cli("compile", "--sketch", closed.getPath()));
        assertEquals(RemoteCLI.EXIT_USAGE, cli("compile", "--sketch", tmp.resolve("missing").toString()));
        assertEquals(RemoteCLI.EXIT_USAGE, cli("compile"));
        assertEquals(RemoteCLI.EXIT_USAGE, cli("compile", "--sketch", blink.getPath(), "--bogus"));
        assertEquals(RemoteCLI.EXIT_USAGE, cli("format-disk"));
    }

    @Test
    void opensSketchesOnlyWhenNeeded() throws Exception {
        startInstance(1, blink);

        assertEquals(RemoteCLI.EXIT_OK, cli("open", "--sketch", blink.getPath()));
        assertEquals(true, lastOutput.get("alreadyOpen"));

        assertEquals(RemoteCLI.EXIT_OK, cli("open", "--sketch", closed.getPath()));
        assertEquals(RemoteProtocol.JOB_DONE, lastOutput.get("state"));
        assertEquals(RemoteCLI.EXIT_OK, cli("compile", "--sketch", closed.getPath()));
    }

    @Test
    void asksToChooseWhenTheTargetIsAmbiguous() throws Exception {
        startInstance(1, blink);
        RemoteServer second = startInstance(2, blink);

        assertEquals(RemoteCLI.EXIT_USAGE, cli("compile", "--sketch", blink.getPath()));
        assertEquals(RemoteProtocol.ERR_AMBIGUOUS, outputMap("error").get("code"));

        assertEquals(RemoteCLI.EXIT_OK, cli("compile", "--sketch", blink.getPath(), "--instance", second.instanceId()));
        assertEquals(second.instanceId(), lastOutput.get("instance"));
    }

    @Test
    void stopsWaitingAtTheTimeoutAndResumesWithTheJobId() throws Exception {
        startInstance(1, slow, blink);

        assertEquals(RemoteCLI.EXIT_TIMEOUT, cli("compile", "--sketch", slow.getPath(), "--timeout", "0"));
        String jobId = (String) lastOutput.get("job");
        assertEquals(RemoteProtocol.ERR_TIMEOUT, outputMap("error").get("code"));

        assertEquals(RemoteCLI.EXIT_BUSY, cli("compile", "--sketch", blink.getPath()));
        assertEquals(RemoteCLI.EXIT_TIMEOUT, cli("job", jobId));

        handlers.get(0).release.countDown();
        assertEquals(RemoteCLI.EXIT_OK, cli("job", jobId, "--wait"));
        assertEquals(RemoteProtocol.JOB_DONE, lastOutput.get("state"));
        assertTrue(String.valueOf(lastOutput.get("log")).contains("compile finished"));
    }

    @Test
    void canReturnRightAfterStartingAJob() throws Exception {
        startInstance(1, blink);

        assertEquals(RemoteCLI.EXIT_OK, cli("apply-markers", "--sketch", blink.getPath(), "--no-wait"));
        assertEquals(RemoteProtocol.JOB_RUNNING, lastOutput.get("state"));

        assertEquals(RemoteCLI.EXIT_OK, cli("job", (String) lastOutput.get("job"), "--wait"));
    }

    @Test
    void routesCommandsWithoutSketchToTheOnlyInstance() throws Exception {
        RemoteServer only = startInstance(1, blink);

        assertEquals(RemoteCLI.EXIT_OK, cli("list-fqbns"));
        assertEquals(only.instanceId(), lastOutput.get("instance"));

        assertEquals(RemoteCLI.EXIT_OK, cli("status", "--sketch", blink.getPath()));
        assertEquals(blink.getPath(), outputMap("sketch").get("folder"));
    }
}
