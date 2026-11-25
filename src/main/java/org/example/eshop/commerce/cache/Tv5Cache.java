package org.example.eshop.commerce.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

// Bộ đệm đọc có giới hạn dung lượng và TTL riêng cho TV5, có thống kê hit/miss để đo cold vs warm cache
// Khi nhóm bật Redis tập trung ở TV1 có thể thay backend lưu trữ bằng RedisCacheManager mà giữ nguyên gọi từ service
@Component
public class Tv5Cache {
    public static final String BANNERS = "content:banners";
    public static final String ARTICLES = "content:articles";
    public static final String DASHBOARD = "analytics:dashboard";
    public static final String RECOMMENDATIONS = "catalog:recommendations";

    private static final int MAX_ENTRIES_PER_NAMESPACE = 250;

    private final Clock clock;
    private final boolean enabled;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, CacheEntry>> stores = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public Tv5Cache(Clock clock, @Value("${commerce.tv5.cache.enabled:true}") boolean enabled) {
        this.clock = clock;
        this.enabled = enabled;
    }

    public record CacheStats(boolean enabled, long hits, long misses, double hitRatePercent, Map<String, Integer> entriesByNamespace) {}

    private record CacheEntry(Object value, long expiresAtMs) {}

    // Đọc từ cache nếu còn hạn; nếu chưa có hoặc hết hạn thì gọi loader để lấy từ DB rồi lưu lại
    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String namespace, String key, Duration ttl, Supplier<T> loader) {
        if (!enabled || ttl == null || ttl.isNegative() || ttl.isZero()) {
            misses.incrementAndGet();
            return loader.get();
        }
        var bucket = stores.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>());
        long now = clock.millis();
        CacheEntry existing = bucket.get(key);
        if (existing != null && existing.expiresAtMs() > now) {
            hits.incrementAndGet();
            return (T) existing.value();
        }
        misses.incrementAndGet();
        T loaded = loader.get();
        if (loaded != null) {
            // Giới hạn số key mỗi namespace để tránh tràn RAM khi người dùng truyền từ khóa tìm kiếm ngẫu nhiên
            if (bucket.size() >= MAX_ENTRIES_PER_NAMESPACE) {
                bucket.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
                if (bucket.size() >= MAX_ENTRIES_PER_NAMESPACE) {
                    bucket.clear();
                }
            }
            bucket.put(key, new CacheEntry(loaded, now + ttl.toMillis()));
        }
        return loaded;
    }

    // Xóa cache của 1 namespace sau khi transaction DB commit thành công (tránh xóa nhầm nếu transaction rollback)
    public void evictAfterCommit(String namespace) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    clearNamespace(namespace);
                }
            });
        } else {
            clearNamespace(namespace);
        }
    }

    public void clearNamespace(String namespace) {
        var bucket = stores.get(namespace);
        if (bucket != null) {
            bucket.clear();
        }
    }

    // Xóa toàn bộ cache TV5 và reset bộ đếm để đo lại kịch bản cold cache
    public void clearAll() {
        stores.values().forEach(Map::clear);
        hits.set(0);
        misses.set(0);
    }

    // Trả thống kê hit/miss hiện tại cho Dashboard quản trị
    public CacheStats stats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        double rate = total == 0 ? 0.0 : Math.round((h * 10000.0) / total) / 100.0;
        long now = clock.millis();
        var counts = new LinkedHashMap<String, Integer>();
        for (String ns : new String[]{BANNERS, ARTICLES, DASHBOARD, RECOMMENDATIONS}) {
            var bucket = stores.get(ns);
            int active = 0;
            if (bucket != null) {
                for (var entry : bucket.values()) {
                    if (entry.expiresAtMs() > now) active++;
                }
            }
            counts.put(ns, active);
        }
        return new CacheStats(enabled, h, m, rate, counts);
    }
}
