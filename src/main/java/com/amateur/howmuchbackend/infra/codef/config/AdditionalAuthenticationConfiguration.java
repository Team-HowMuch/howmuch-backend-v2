package com.amateur.howmuchbackend.infra.codef.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "howmuch.codef.additional-auth", name = "enabled", havingValue = "true")
public class AdditionalAuthenticationConfiguration {
    @Bean("financeAuthenticationScheduler")
    ThreadPoolTaskScheduler financeAuthenticationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        // Keep SMS deadlines/recovery independent of long initial certificate provider requests.
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("finance-authentication-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
