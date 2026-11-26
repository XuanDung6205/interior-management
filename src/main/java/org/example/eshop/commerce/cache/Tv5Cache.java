package org.example.eshop.commerce.cache;

import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

// Bộ đệm đọc có giới hạn dung lượng và TTL riêng cho TV5, có thống kê hit/miss để đo cold vs warm cache
// Khi nhóm bật Redis tập trung ở TV1 có thể thay backend lưu trữ bằng RedisCacheManager mà giữ nguyên gọi từ service
@Component
public class Tv5Cache {
    private static final Logger log = LoggerFactory.getLogger(Tv5Cache.class);
    public static final String BANNERS = "content:banners";
    public static final String ARTICLES = "content:articles";
    public static final String DASHBOARD = "analytics:dashboard";
    public static final String RECOMMENDATIONS = "catalog:recommendations";

    private static final int MAX_ENTRIES_PER_NAMESPACE = 250;

    private final Clock clock;
    private final boolean enabled;
    private final Object cacheLock = new Object();
    private final Map<String, Namespace> stores = new HashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public Tv5Cache(Clock clock, @Value("${commerce.tv5.cache.enabled:true}") boolean enabled) {
        this.clock = clock;
        this.enabled = enabled;
    }

    public record CacheStats(boolean enabled, long hits, long misses, double hitRatePercent, Map<String, Integer> entriesByNamespace) {}

    private record CacheEntry(Object value, long expiresAtMs) {}

    private static final class Namespace {
        final Map<String, CacheEntry> entries = new HashMap<>();
        long generation;
    }

    // Đọc từ cache nếu còn hạn; nếu chưa có hoặc hết hạn thì gọi loader để lấy từ DB rồi lưu lại
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String namespace, String key, Duration ttl, Supplier<T> loader) {
        if (!enabled || ttl == null || ttl.isNegative() || ttl.isZero()) {
            misses.incrementAndGet();
            return loader.get();
        }
        Namespace bucket;
        long generation;
        synchronized (cacheLock) {
            bucket = stores.computeIfAbsent(namespace, k -> new Namespace());
            generation = bucket.generation;
            CacheEntry existing = bucket.entries.get(key);
            if (existing != null && existing.expiresAtMs() > clock.millis()) {
                hits.incrementAndGet();
                return (T) existing.value();
            }
        }
        misses.incrementAndGet();
        // Database/provider work must never hold the cache lock. A generation change
        // fences this loader if a committed write invalidated its earlier snapshot.
        T loaded = loader.get();
        if (loaded != null) {
            synchronized (cacheLock) {
                if (generation != bucket.generation) return loaded;
                long now = clock.millis();
                // Serialize the capacity check with publication, including concurrent loaders.
                if (bucket.entries.size() >= MAX_ENTRIES_PER_NAMESPACE) {
                    bucket.entries.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
                    if (bucket.entries.size() >= MAX_ENTRIES_PER_NAMESPACE) bucket.entries.clear();
                }
                bucket.entries.put(key, new CacheEntry(loaded, now + ttl.toMillis()));
            }
        }
        return loaded;
    }

    // Xóa cache của 1 namespace sau khi transaction DB commit thành công (tránh xóa nhầm nếu transaction rollback)
    public void evictAfterCommit(String namespace) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictSafely(namespace);
                }
            });
        } else {
            evictSafely(namespace);
        }
    }

    private void evictSafely(String namespace) {
        try {
            clearNamespace(namespace);
        } catch (RuntimeException ex) {
            // The database already committed: cache housekeeping must not turn a
            // successful collection/refund into a failed response and unsafe retry.
            log.warn("TV5 cache invalidation failed for namespace {}; TTL still bounds cached entries", namespace);
        }
    }

    public void clearNamespace(String namespace) {
        synchronized (cacheLock) {
            var bucket = stores.get(namespace);
            if (bucket != null) {
                bucket.generation++;
                bucket.entries.clear();
            }
        }
    }

    // Xóa toàn bộ cache TV5 và reset bộ đếm để đo lại kịch bản cold cache
    public void clearAll() {
        synchronized (cacheLock) {
            stores.values().forEach(bucket -> {
                bucket.generation++;
                bucket.entries.clear();
            });
            hits.set(0);
            misses.set(0);
        }
    }

    // Trả thống kê hit/miss hiện tại cho Dashboard quản trị
    public CacheStats stats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        double rate = total == 0 ? 0.0 : Math.round((h * 10000.0) / total) / 100.0;
        long now = clock.millis();
        var counts = new LinkedHashMap<String, Integer>();
        synchronized (cacheLock) {
            for (String ns : new String[]{BANNERS, ARTICLES, DASHBOARD, RECOMMENDATIONS}) {
                var bucket = stores.get(ns);
                int active = 0;
                if (bucket != null) {
                    for (var entry : bucket.entries.values()) {
                        if (entry.expiresAtMs() > now) active++;
                    }
                }
                counts.put(ns, active);
            }
        }
        return new CacheStats(enabled, h, m, rate, counts);
    }
}
