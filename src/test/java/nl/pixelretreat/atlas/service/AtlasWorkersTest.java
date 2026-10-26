package nl.pixelretreat.atlas.service;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AtlasWorkersTest {
    @Test void optionalObservationsYieldToBusinessAndNeverUseTheOnlyWorker() throws Exception {
        var ran=new AtomicBoolean();
        try(var single=new AtlasWorkers(1,2)) {
            single.submitObservation(()->{ran.set(true);return null;}).get(1,TimeUnit.SECONDS);
            assertFalse(ran.get());
            single.submitRecovery(()->{ran.set(true);return null;}).get(1,TimeUnit.SECONDS);
            assertTrue(ran.get());ran.set(false);
            assertEquals(7,single.submit(()->7).get(1,TimeUnit.SECONDS));
        }
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var workers=new AtlasWorkers(2,2)) {
            var business=workers.submit(()->{entered.countDown();release.await();return 9;});
            try {
                assertTrue(entered.await(1,TimeUnit.SECONDS));
                workers.submitObservation(()->{ran.set(true);return null;}).get(1,TimeUnit.SECONDS);
                assertFalse(ran.get());
                assertEquals(11,workers.submit(()->11).get(1,TimeUnit.SECONDS));
            }finally{release.countDown();}
            assertEquals(9,business.get(1,TimeUnit.SECONDS));
        }
    }
    @Test void shutdownSettlesQueuedWorkWithoutPretendingActiveWorkHasRetired() throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var ranQueued = new AtomicBoolean();
        try (var workers = new AtlasWorkers(1, 1)) {
            var active = workers.submit(() -> {
                entered.countDown();
                try { finish.await(); }
                catch (InterruptedException ignored) { interrupted.countDown(); finish.await(); }
                return 7;
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var queued = workers.submit(() -> { ranQueued.set(true); return 8; });
                assertInstanceOf(RejectedExecutionException.class,
                        assertThrows(ExecutionException.class, () -> workers.submit(() -> 9).get()).getCause());
                workers.close();
                assertThrows(CancellationException.class, queued::join);
                assertTrue(interrupted.await(5, TimeUnit.SECONDS));
                assertFalse(active.isDone());
                assertFalse(ranQueued.get());
                assertInstanceOf(RejectedExecutionException.class,
                        assertThrows(ExecutionException.class, () -> workers.submit(() -> 10).get()).getCause());
            } finally { finish.countDown(); }
            assertEquals(7, active.get(5, TimeUnit.SECONDS));
        }
    }
}
