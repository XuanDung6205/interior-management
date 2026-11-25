package org.example.eshop.commerce.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class SePayAutomationSettings {
    public final boolean enabled;
    public final int ttlSeconds,intervalSeconds,batchSize,lateHours;
    public SePayAutomationSettings(@Value("${commerce.payments.sepay.automation-enabled:true}")boolean enabled,
            @Value("${commerce.payments.sepay.payment-ttl-seconds:1800}")int ttl,
            @Value("${commerce.payments.sepay.reconciliation-interval-seconds:60}")int interval,
            @Value("${commerce.payments.sepay.batch-size:5}")int batch,
            @Value("${commerce.payments.sepay.late-reconciliation-hours:72}")int late){
        if(ttl<60||ttl>86400||interval<30||interval>3600||batch<1||batch>20||late<1||late>720)throw new IllegalStateException("SePay automation configuration outside safe bounds.");
        this.enabled=enabled;ttlSeconds=ttl;intervalSeconds=interval;batchSize=batch;lateHours=late;
    }
    public Map<String,Object> view(SePaySettings settings){return Map.of("enabled",enabled&&settings.canReconcile(),"ttlSeconds",ttlSeconds,"intervalSeconds",intervalSeconds);}
}
