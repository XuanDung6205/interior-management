package org.example.eshop.commerce.payment;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
@Component
public class SePayAutomationJob {
    private final SePayAutomationService service;private final SePayRefundService refunds;private final SePayAutomationSettings config;private final boolean jobs;
    public SePayAutomationJob(SePayAutomationService service,SePayRefundService refunds,SePayAutomationSettings config,@Value("${commerce.jobs.enabled:true}")boolean jobs){this.service=service;this.refunds=refunds;this.config=config;this.jobs=jobs;}
    @Scheduled(fixedDelayString="${commerce.payments.sepay.reconciliation-interval-seconds:60}000",initialDelayString="${commerce.payments.sepay.reconciliation-interval-seconds:60}000")
    public void run(){if(jobs&&config.enabled){service.runBatch();refunds.runDue();}}
}
