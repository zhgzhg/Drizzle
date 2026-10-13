package com.github.zhgzhg.drizzle.remote;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityGuardTest {

    @AfterEach
    void forgetActivities() {
        ActivityGuard.reset();
    }

    @Test
    void remoteActivityExcludesOtherActivities() throws Exception {
        try (ActivityGuard.Lease remote = ActivityGuard.tryRemote("remote compile")) {
            assertNotNull(remote);
            assertTrue(remote.isHeld());
            assertEquals("remote compile", ActivityGuard.busyDescription());
            assertNull(ActivityGuard.tryRemote("another"));

            AtomicReference<ActivityGuard.Lease> fromOtherThread = new AtomicReference<>();
            Thread t = new Thread(() -> fromOtherThread.set(ActivityGuard.tryLocal("Verify")));
            t.start();
            t.join();
            assertNull(fromOtherThread.get(), "the IDE can't start an action while a remote command runs");

            try (ActivityGuard.Lease nested = ActivityGuard.tryLocal("Verify")) {
                assertNotNull(nested, "the remote command's own thread may go through the IDE's hooks");
            }
            assertEquals("remote compile", ActivityGuard.busyDescription());
        }
        assertNull(ActivityGuard.busyDescription());
    }

    @Test
    void localActivitiesRunTogetherButBlockRemoteOnes() {
        ActivityGuard.Lease first = ActivityGuard.tryLocal("Apply Markers");
        ActivityGuard.Lease second = ActivityGuard.tryLocal("Verify/Upload");
        assertNotNull(first);
        assertNotNull(second);
        assertNull(ActivityGuard.tryRemote("remote compile"));
        assertEquals("Apply Markers", ActivityGuard.busyDescription());

        first.close();
        first.close(); // closing twice must not release someone else's activity
        assertFalse(first.isHeld());
        assertTrue(second.isHeld());
        assertEquals("Verify/Upload", ActivityGuard.busyDescription(), "names what still runs");
        assertNull(ActivityGuard.tryRemote("remote compile"));

        second.close();
        assertNull(ActivityGuard.busyDescription());
        try (ActivityGuard.Lease remote = ActivityGuard.tryRemote("remote compile")) {
            assertNotNull(remote);
        }
    }

    @Test
    void cancellingFreesTheIdeAndInterruptsTheCommand() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        AtomicReference<ActivityGuard.Lease> lease = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();

        Thread command = new Thread(() -> {
            lease.set(ActivityGuard.tryRemote("remote apply-markers"));
            holding.countDown();
            try {
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
        });
        command.start();
        assertTrue(holding.await(10, TimeUnit.SECONDS));

        assertEquals("remote apply-markers", ActivityGuard.cancelRemote());
        command.join(10000);
        assertTrue(interrupted.get());
        assertFalse(lease.get().isHeld());
        assertNull(ActivityGuard.busyDescription());
        assertNull(ActivityGuard.cancelRemote(), "nothing left to cancel");

        try (ActivityGuard.Lease next = ActivityGuard.tryRemote("remote compile")) {
            lease.get().close(); // the cancelled command finishing late must not free the next one
            assertTrue(next.isHeld());
            assertEquals("remote compile", ActivityGuard.busyDescription());
        }
    }
}
