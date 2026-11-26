package org.example.eshop.commerce;

import org.example.eshop.commerce.cache.Tv5Cache;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class Tv5CacheConcurrencyTest {
    private final Tv5Cache cache = new Tv5Cache(Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC), true);
    private static final Duration TTL = Duration.ofMinutes(2);

    @Test
    void invalidationFencesLoaderThatReadBeforeTheCommit() throws Exception {
        assertOldLoaderCannotPublish(() -> cache.clearNamespace(Tv5Cache.DASHBOARD));
    }

    @Test
    void clearAllAlsoFencesAnInFlightLoader() throws Exception {
        assertOldLoaderCannotPublish(cache::clearAll);
    }

    private void assertOldLoaderCannotPublish(Runnable evict) throws Exception {
        var readOldValue = new CountDownLatch(1);
        var finishOldLoad = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var oldRequest = executor.submit(() -> cache.getOrLoad(Tv5Cache.DASHBOARD, "days:30", TTL, () -> {
                readOldValue.countDown();
                try {
                    if (!finishOldLoad.await(5, TimeUnit.SECONDS)) throw new AssertionError("Loader was not released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return "before payment";
            }));
            assertTrue(readOldValue.await(5, TimeUnit.SECONDS));
            evict.run();
            finishOldLoad.countDown();
            assertEquals("before payment", oldRequest.get(5, TimeUnit.SECONDS));
            assertEquals("after payment", cache.getOrLoad(Tv5Cache.DASHBOARD, "days:30", TTL, () -> "after payment"));
            assertEquals("after payment", cache.getOrLoad(Tv5Cache.DASHBOARD, "days:30", TTL, () -> fail("Fresh value should be cached")));
        } finally {
            finishOldLoad.countDown();
            executor.shutdownNow();
        }
    }
}
