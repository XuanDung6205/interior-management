package org.example.eshop.commerce.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/** Public DTOs only. Redis is optional; database/loader failures must propagate. */
@Component
public class CatalogCache {
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final boolean enabled;
    final String prefix;
    private volatile long retryAt;
    private volatile boolean invalidationPending;
    public CatalogCache(StringRedisTemplate redis, ObjectMapper mapper,
                        @Value("${commerce.catalog.cache.enabled:true}") boolean enabled,
                        @Value("${spring.datasource.url:catalog}") String database) {
        this.redis=redis; this.mapper=mapper; this.enabled=enabled;
        this.prefix="catalog:v1:"+UUID.nameUUIDFromBytes(database.getBytes(StandardCharsets.UTF_8))+":";
    }
    private String generation() {
        if (invalidationPending) {
            redis.opsForValue().set(prefix+"generation", UUID.randomUUID().toString());
            invalidationPending=false;
        }
        String value=redis.opsForValue().get(prefix+"generation");
        return value==null ? "0" : value;
    }
    public <T> T read(String key, Class<T> type, Supplier<T> loader) {
        if (!enabled || System.currentTimeMillis()<retryAt) return loader.get();
        String generation=null, dataKey=null;
        try {
            generation=generation();
            dataKey=prefix+generation+":"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
            String data=redis.opsForValue().get(dataKey);
            if (data!=null) return mapper.readValue(data,type);
        } catch (Exception unavailable) { retryAt=System.currentTimeMillis()+5000; }
        T value=loader.get();
        if (dataKey!=null && System.currentTimeMillis()>=retryAt) {
            try {
                if (generation.equals(generation())) redis.opsForValue().set(dataKey,mapper.writeValueAsString(value),Duration.ofSeconds(60));
            } catch (RuntimeException unavailable) { retryAt=System.currentTimeMillis()+5000; }
        }
        return value;
    }
    public void evictAfterCommit() {
        Runnable evict=()-> {
            invalidationPending=true;
            if (!enabled) return;
            try { generation(); retryAt=0; }
            catch (RuntimeException unavailable) { retryAt=System.currentTimeMillis()+5000; }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { evict.run(); }
            });
        } else evict.run();
    }
}
