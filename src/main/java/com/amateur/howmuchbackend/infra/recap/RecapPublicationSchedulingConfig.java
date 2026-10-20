package com.amateur.howmuchbackend.infra.recap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "howmuch.recap.publication", name = "enabled", havingValue = "true")
public class RecapPublicationSchedulingConfig {
    // A dedicated scheduler suppresses Boot's default, so retain a shared scheduler for other jobs.
    @Bean("taskScheduler")
    @ConditionalOnMissingBean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }

    @Bean
    public ThreadPoolTaskScheduler recapPublicationTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        // Kafka ACK deadlines must not delay notification delivery, recap generation, or reconciliation.
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("recap-publication-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
