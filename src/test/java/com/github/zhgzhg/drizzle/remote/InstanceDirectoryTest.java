package com.github.zhgzhg.drizzle.remote;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceDirectoryTest {

    @TempDir
    Path tmp;

    static InstanceInfo sampleInfo(final long pid) {
        InstanceInfo info = new InstanceInfo();
        info.instanceId = "id" + pid;
        info.pid = pid;
        info.host = "127.0.0.1";
        info.port = 4242;
        info.token = "secret";
        info.drizzleVersion = "0.17.0";
        info.ideVersion = "1.8.19";
        info.startedAt = "2026-10-12T10:00:00Z";
        return info;
    }

    @Test
    void writesAndReadsBackEveryInstance() throws Exception {
        InstanceDirectory directory = new InstanceDirectory(tmp.resolve("drizzle-test"));

        InstanceInfo first = sampleInfo(100);
        first.sketches = Arrays.asList("C:\\Users\\Иван\\Arduino\\blink\\blink.ino", "/home/a b/x=y:z#1/x=y:z#1.ino");
        directory.write(first);
        directory.write(sampleInfo(200));

        List<InstanceInfo> all = directory.readAll();
        assertEquals(2, all.size());

        InstanceInfo read = all.stream().filter(i -> i.pid == 100).findFirst().get();
        assertEquals("id100", read.instanceId);
        assertEquals("http://127.0.0.1:4242", read.baseUrl());
        assertEquals("secret", read.token);
        assertEquals(first.sketches, read.sketches);
        assertEquals(directory.fileOf(100), read.file);
    }

    @Test
    void rewritingReplacesTheFileWithoutLeavingTemporaryFiles() throws Exception {
        InstanceDirectory directory = new InstanceDirectory(tmp);

        InstanceInfo info = sampleInfo(100);
        directory.write(info);
        info.sketches = Arrays.asList("/sketches/blink/blink.ino");
        directory.write(info);

        assertEquals(1, directory.readAll().size());
        assertEquals(info.sketches, directory.readAll().get(0).sketches);
        try (java.util.stream.Stream<Path> files = Files.list(tmp)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void skipsMalformedFilesAndDeletesOnRequest() throws Exception {
        InstanceDirectory directory = new InstanceDirectory(tmp);
        Files.write(tmp.resolve("instance-7.properties"), "port=1\n".getBytes(StandardCharsets.ISO_8859_1));
        InstanceInfo badPort = sampleInfo(6);
        badPort.port = 70000;
        directory.write(badPort);
        Path good = directory.write(sampleInfo(8));

        assertEquals(1, directory.readAll().size());

        directory.delete(good);
        assertFalse(Files.exists(good));
        assertTrue(directory.readAll().isEmpty());
    }

    @Test
    void ipv6HostsAreBracketedInUrls() {
        InstanceInfo info = sampleInfo(1);
        info.host = "0:0:0:0:0:0:0:1";
        assertEquals("http://[0:0:0:0:0:0:0:1]:4242", info.baseUrl());
    }
}
