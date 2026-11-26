package org.example.eshop.commerce.vector;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VectorJob {
    private final VectorRuntime runtime;
    private final VectorSettings settings;
    private final boolean jobsEnabled;
    public VectorJob(VectorRuntime runtime, VectorSettings settings, @Value("${commerce.jobs.enabled:${COMMERCE_JOBS_ENABLED:true}}") boolean jobsEnabled) {
        this.runtime = runtime; this.settings = settings; this.jobsEnabled = jobsEnabled;
    }
    @Scheduled(fixedDelayString="${commerce.vector.interval-seconds:${VECTOR_INTERVAL_SECONDS:60}}", initialDelayString="${commerce.vector.interval-seconds:${VECTOR_INTERVAL_SECONDS:60}}", timeUnit=java.util.concurrent.TimeUnit.SECONDS)
    public void run() {
        if (!jobsEnabled || !settings.enabled() || !settings.configured()) return;
        try { runtime.runBatch(); }
        catch (RuntimeException unavailable) { /* Durable tasks remain queued; provider/database details are never logged. */ }
    }
}
