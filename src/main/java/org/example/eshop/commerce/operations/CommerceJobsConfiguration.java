package org.example.eshop.commerce.operations;
import org.example.eshop.commerce.order.OrderExpiryJob;
import org.example.eshop.commerce.notification.NotificationJob;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
@Configuration @EnableScheduling
public class CommerceJobsConfiguration {
    private final OrderExpiryJob expiry;private final NotificationJob notifications;private final boolean enabled,expiryEnabled,notificationEnabled;
    public CommerceJobsConfiguration(OrderExpiryJob expiry,NotificationJob notifications,@Value("${commerce.jobs.enabled:true}")boolean enabled,@Value("${commerce.jobs.expiry-enabled:true}")boolean expiryEnabled,@Value("${commerce.jobs.notification-enabled:true}")boolean notificationEnabled){this.expiry=expiry;this.notifications=notifications;this.enabled=enabled;this.expiryEnabled=expiryEnabled;this.notificationEnabled=notificationEnabled;}
    @Scheduled(fixedDelayString="${commerce.jobs.delay-ms:10000}",initialDelayString="${commerce.jobs.delay-ms:10000}") public void expire(){if(enabled&&expiryEnabled)expiry.runBatch();}
    @Scheduled(fixedDelayString="${commerce.jobs.delay-ms:10000}",initialDelayString="${commerce.jobs.delay-ms:10000}") public void deliver(){if(enabled&&notificationEnabled)notifications.runBatch();}
}
