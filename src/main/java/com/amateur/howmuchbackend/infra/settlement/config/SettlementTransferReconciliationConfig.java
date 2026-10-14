package com.amateur.howmuchbackend.infra.settlement.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties(SettlementTransferReconciliationProperties.class)
@ConditionalOnProperty(prefix="howmuch.settlement.transfer-reconciliation", name="enabled", havingValue="true")
public class SettlementTransferReconciliationConfig {
    // 전용 scheduler가 Boot의 기본 scheduler 자동 구성을 대체하지 않도록 공용 scheduler도 둔다.
    @Bean("taskScheduler")
    @ConditionalOnMissingBean(name="taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("scheduling-");
        return scheduler;
    }

    @Bean
    public ThreadPoolTaskScheduler settlementTransferReconciliationTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("settlement-transfer-reconciliation-");
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
