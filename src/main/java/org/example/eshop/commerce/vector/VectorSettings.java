package org.example.eshop.commerce.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class VectorSettings {
    public static final String MODEL = "gemini-embedding-001";
    public static final int DIMENSIONS = 768;
    private final boolean enabled;
    private final String apiKey;
    private final int batchSize;
    private final long intervalSeconds;
    private final int maxAttempts;

    public VectorSettings(@Value("${commerce.vector.enabled:${VECTOR_ENABLED:false}}") boolean enabled,
                          @Value("${spring.ai.gemini.api-key:${GEMINI_API_KEY:}}") String apiKey,
                          @Value("${commerce.vector.batch-size:${VECTOR_BATCH_SIZE:5}}") int batchSize,
                          @Value("${commerce.vector.interval-seconds:${VECTOR_INTERVAL_SECONDS:60}}") long intervalSeconds,
                          @Value("${commerce.vector.max-attempts:${VECTOR_MAX_ATTEMPTS:8}}") int maxAttempts) {
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.batchSize = Math.max(1, Math.min(100, batchSize));
        this.intervalSeconds = Math.max(1, Math.min(86400, intervalSeconds));
        this.maxAttempts = Math.max(1, Math.min(20, maxAttempts));
    }
    public boolean enabled() { return enabled; }
    public boolean configured() { return !apiKey.isEmpty(); }
    String apiKey() { return apiKey; }
    public int batchSize() { return batchSize; }
    public long intervalSeconds() { return intervalSeconds; }
    public int maxAttempts() { return maxAttempts; }
}
