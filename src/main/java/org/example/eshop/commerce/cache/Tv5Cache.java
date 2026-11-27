package org.example.eshop.commerce.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;

// Bộ đệm đọc cho TV5: ưu tiên lưu JSON vào Redis (có TTL & khóa generation),
// nhưng nếu Redis tắt/lỗi thì tự bật chế độ dự phòng về RAM cục bộ để web không bao giờ sập
@Component
public class Tv5Cache {
    private static final Logger log = LoggerFactory.getLogger(Tv5Cache.class);
    public static final String BANNERS = "content:banners";
    public static final String ARTICLES = "content:articles";
    public static final String DASHBOARD = "analytics:dashboard";
    public static final String RECOMMENDATIONS = "catalog:recommendations";

    private static final String[] ALL_NAMESPACES = {BANNERS, ARTICLES, DASHBOARD, RECOMMENDATIONS};
    private static final int MAX_ENTRIES_PER_NAMESPACE = 250;
    // Khi Redis rớt mạng thì tạm né 30 giây rồi mới thử kết nối lại để khỏi làm chậm mọi request
    private static final long REDIS_COOLDOWN_MS = 30_000L;

    private final Clock clock;
    private final boolean enabled;
    private final Object cacheLock = new Object();
    private final Map<String, Namespace> stores = new HashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    private volatile boolean redisEnabled = true;
    private volatile StringRedisTemplate redisTemplate;
    private volatile ObjectMapper objectMapper;
    private volatile long redisUnavailableUntilMs = 0L;

    @Autowired
    public Tv5Cache(Clock clock, @Value("${commerce.tv5.cache.enabled:true}") boolean enabled) {
        this.clock = clock;
        this.enabled = enabled;
    }

    // Constructor đầy đủ để unit test tiêm mock Redis & kiểm tra kịch bản Redis sống/chết dễ dàng
    public Tv5Cache(Clock clock, boolean enabled, boolean redisEnabled,
                    StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.clock = clock;
        this.enabled = enabled;
        this.redisEnabled = redisEnabled;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Autowired(required = false)
    public void configureRedis(
            @Value("${commerce.tv5.cache.redis-enabled:true}") boolean redisEnabled,
            @Autowired(required = false) StringRedisTemplate redisTemplate,
            @Autowired(required = false) ObjectMapper objectMapper) {
        this.redisEnabled = redisEnabled;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public record CacheStats(
            boolean enabled,
            String backend,
            long hits,
            long misses,
            double hitRatePercent,
            Map<String, Integer> entriesByNamespace) {}

    private record CacheEntry(Object value, long expiresAtMs) {}

    private static final class Namespace {
        final Map<String, CacheEntry> entries = new HashMap<>();
        long generation;
    }

    // Bản cũ giữ lại để tương thích ngược (dùng cache RAM cục bộ khi không truyền kiểu JSON)
    public <T> T getOrLoad(String namespace, String key, Duration ttl, Supplier<T> loader) {
        return getOrLoadInternal(namespace, key, ttl, null, loader);
    }

    // Đọc/ghi cache dạng đối tượng cụ thể (record/DTO) — hỗ trợ đầy đủ Redis JSON + fallback RAM
    public <T> T getOrLoad(String namespace, String key, Duration ttl, Class<T> type, Supplier<T> loader) {
        return getOrLoadInternal(namespace, key, ttl,
                json -> objectMapper.readValue(json, type), loader);
    }

    // Đọc/ghi cache dạng generic như List<BannerView> — hỗ trợ đầy đủ Redis JSON + fallback RAM
    public <T> T getOrLoad(String namespace, String key, Duration ttl, TypeReference<T> typeRef, Supplier<T> loader) {
        return getOrLoadInternal(namespace, key, ttl,
                json -> objectMapper.readValue(json, typeRef), loader);
    }

    private <T> T getOrLoadInternal(
            String namespace, String key, Duration ttl,
            Function<String, T> jsonDeserializer, Supplier<T> loader) {
        if (!enabled || ttl == null || ttl.isNegative() || ttl.isZero()) {
            misses.incrementAndGet();
            return loader.get();
        }

        // Ưu tiên thử đọc/ghi trên Redis nếu đang bật và chưa vào trạng thái cooldown lỗi
        if (jsonDeserializer != null && isRedisUsable()) {
            String generation = null;
            String redisKey = null;
            boolean redisReadOk = false;
            try {
                generation = readRedisGeneration(namespace);
                redisKey = redisDataKey(namespace, generation, key);
                String cachedJson = redisTemplate.opsForValue().get(redisKey);
                if (cachedJson != null) {
                    T decoded = jsonDeserializer.apply(cachedJson);
                    if (decoded != null) {
                        hits.incrementAndGet();
                        return decoded;
                    }
                }
                redisReadOk = true;
            } catch (RuntimeException ex) {
                markRedisUnavailable(ex);
            }

            if (redisReadOk) {
                // Redis miss: gọi DB loader ở ngoài mọi lock & ngoài try/catch Redis để lỗi DB không bị nuốt
                misses.incrementAndGet();
                T loaded = loader.get();
                if (loaded != null) {
                    try {
                        // Kiểm tra lại generation để tránh ghi đè dữ liệu cũ nếu có giao dịch vừa commit xóa cache
                        if (generation.equals(readRedisGeneration(namespace))) {
                            String serialized = objectMapper.writeValueAsString(loaded);
                            redisTemplate.opsForValue().set(redisKey, serialized, ttl);
                        }
                    } catch (RuntimeException ex) {
                        markRedisUnavailable(ex);
                        putLocalBestEffort(namespace, key, loaded, ttl);
                    }
                }
                return loaded;
            }
        }

        // Đường dự phòng bằng RAM cục bộ khi tắt Redis hoặc khi Redis tạm mất kết nối
        return getOrLoadLocal(namespace, key, ttl, loader);
    }

    @SuppressWarnings("unchecked")
    private <T> T getOrLoadLocal(String namespace, String key, Duration ttl, Supplier<T> loader) {
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
        // Gọi DB ngoài lock để không chặn luồng khác; so sánh generation tránh ghi ngược bản cũ sau khi xóa cache
        T loaded = loader.get();
        if (loaded != null) {
            synchronized (cacheLock) {
                if (generation != bucket.generation) return loaded;
                long now = clock.millis();
                if (bucket.entries.size() >= MAX_ENTRIES_PER_NAMESPACE) {
                    bucket.entries.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
                    if (bucket.entries.size() >= MAX_ENTRIES_PER_NAMESPACE) bucket.entries.clear();
                }
                bucket.entries.put(key, new CacheEntry(loaded, now + ttl.toMillis()));
            }
        }
        return loaded;
    }

    private void putLocalBestEffort(String namespace, String key, Object value, Duration ttl) {
        synchronized (cacheLock) {
            Namespace bucket = stores.computeIfAbsent(namespace, k -> new Namespace());
            long now = clock.millis();
            if (bucket.entries.size() >= MAX_ENTRIES_PER_NAMESPACE) {
                bucket.entries.entrySet().removeIf(entry -> entry.getValue().expiresAtMs() <= now);
                if (bucket.entries.size() >= MAX_ENTRIES_PER_NAMESPACE) bucket.entries.clear();
            }
            bucket.entries.put(key, new CacheEntry(value, now + ttl.toMillis()));
        }
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
            // DB đã commit xong rồi: lỗi dọn cache tuyệt đối không được biến giao dịch thành công thành lỗi 500
            log.warn("Xóa cache TV5 thất bại cho namespace {}; dữ liệu vẫn bị giới hạn bởi TTL", namespace);
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
        // Tăng bộ đếm generation trong Redis (O(1), không dùng KEYS *) để vô hiệu hóa toàn bộ key cũ ngay lập tức
        if (isRedisUsable()) {
            try {
                redisTemplate.opsForValue().increment(redisGenKey(namespace));
            } catch (RuntimeException ex) {
                markRedisUnavailable(ex);
            }
        }
    }

    // Xóa toàn bộ cache TV5 (cả RAM lẫn Redis) và reset bộ đếm hit/miss để đo lại cold cache
    public void clearAll() {
        synchronized (cacheLock) {
            stores.values().forEach(bucket -> {
                bucket.generation++;
                bucket.entries.clear();
            });
            hits.set(0);
            misses.set(0);
        }
        if (isRedisUsable()) {
            try {
                for (String ns : ALL_NAMESPACES) {
                    redisTemplate.opsForValue().increment(redisGenKey(ns));
                }
            } catch (RuntimeException ex) {
                markRedisUnavailable(ex);
            }
        }
    }

    // Trả thống kê hit/miss và chế độ backend hiện tại (REDIS / MEMORY_FALLBACK / MEMORY / DISABLED) cho Dashboard
    public CacheStats stats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        double rate = total == 0 ? 0.0 : Math.round((h * 10000.0) / total) / 100.0;
        long now = clock.millis();
        var counts = new LinkedHashMap<String, Integer>();
        synchronized (cacheLock) {
            for (String ns : ALL_NAMESPACES) {
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
        return new CacheStats(enabled, currentBackendLabel(now), h, m, rate, counts);
    }

    private boolean isRedisUsable() {
        return redisEnabled
                && redisTemplate != null
                && objectMapper != null
                && clock.millis() >= redisUnavailableUntilMs;
    }

    private String currentBackendLabel(long nowMs) {
        if (!enabled) return "DISABLED";
        if (!redisEnabled || redisTemplate == null || objectMapper == null) return "MEMORY";
        return nowMs < redisUnavailableUntilMs ? "MEMORY_FALLBACK" : "REDIS";
    }

    private void markRedisUnavailable(RuntimeException ex) {
        long now = clock.millis();
        boolean firstNotice = now >= redisUnavailableUntilMs;
        redisUnavailableUntilMs = now + REDIS_COOLDOWN_MS;
        if (firstNotice) {
            log.warn("Redis tạm không khả dụng ({}); TV5 tự chuyển sang cache RAM dự phòng trong {}s",
                    ex.getClass().getSimpleName(), REDIS_COOLDOWN_MS / 1000);
        }
    }

    private String readRedisGeneration(String namespace) {
        String raw = redisTemplate.opsForValue().get(redisGenKey(namespace));
        return (raw == null || raw.isBlank()) ? "0" : raw.trim();
    }

    private static String redisGenKey(String namespace) {
        return "tv5:gen:" + namespace;
    }

    private static String redisDataKey(String namespace, String generation, String key) {
        return "tv5:" + namespace + ":g" + generation + ":" + key;
    }
}
