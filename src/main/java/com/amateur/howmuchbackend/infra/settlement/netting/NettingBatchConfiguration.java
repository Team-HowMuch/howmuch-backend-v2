package com.amateur.howmuchbackend.infra.settlement.netting;

import com.amateur.howmuchbackend.adapter.in.batch.settlement.NettingExecutionTasklet;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.ProcessNettingExecutionUseCase;

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class NettingBatchConfiguration extends JdbcDefaultBatchConfiguration {
    private final DataSource dataSource;

    public NettingBatchConfiguration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    protected DataSource getDataSource() {
        return dataSource;
    }

    @Override
    protected PlatformTransactionManager getTransactionManager() {
        return new JdbcTransactionManager(dataSource);
    }

    @Bean
    Job groupNettingJob(
            JobRepository repository,
            ProcessNettingExecutionUseCase worker,
            tools.jackson.databind.ObjectMapper mapper) {
        // Tasklets own short application transactions; bank I/O must not hold a DB transaction.
        var stepTransactionManager = getTransactionManager();
        var noDatabaseTransaction =
                new org.springframework.transaction.interceptor.DefaultTransactionAttribute(
                        org.springframework.transaction.TransactionDefinition
                                .PROPAGATION_NOT_SUPPORTED);
        var sync =
                new StepBuilder("groupNetting.synchronize", repository)
                        .tasklet(
                                new NettingExecutionTasklet(worker, true, mapper),
                                stepTransactionManager)
                        .transactionAttribute(noDatabaseTransaction)
                        .build();
        var commit =
                new StepBuilder("groupNetting.commit", repository)
                        .tasklet(
                                new NettingExecutionTasklet(worker, false, mapper),
                                stepTransactionManager)
                        .transactionAttribute(noDatabaseTransaction)
                        .build();
        return new JobBuilder("groupSettlementNetting", repository)
                .start(sync)
                .next(commit)
                .build();
    }

    @Bean
    ThreadPoolTaskScheduler nettingJobTaskScheduler() {
        return scheduler("netting-worker-");
    }

    @Bean
    ThreadPoolTaskScheduler nettingHeartbeatScheduler() {
        return scheduler("netting-heartbeat-");
    }

    @Bean
    ThreadPoolTaskScheduler nettingReminderScheduler() {
        return scheduler("netting-reminder-");
    }

    private ThreadPoolTaskScheduler scheduler(String prefix) {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(prefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
