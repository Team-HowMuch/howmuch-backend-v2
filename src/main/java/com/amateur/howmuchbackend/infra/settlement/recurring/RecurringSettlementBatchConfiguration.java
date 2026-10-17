package com.amateur.howmuchbackend.infra.settlement.recurring;

import com.amateur.howmuchbackend.adapter.in.batch.settlement.RecurringSettlementTasklet;
import com.amateur.howmuchbackend.application.port.in.settlement.DispatchRecurringSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ProcessRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RecordRecurringSettlementFailureUseCase;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;

/**
 * 반복 발행 Job. 기존 압축 정산(Netting)과 JDBC JobRepository·JobOperator 메타데이터를 공유하며,
 * 새 메타데이터 테이블을 만들지 않는다. Tasklet은 업무 트랜잭션을 스스로 열지 않고 UseCase에 맡긴다.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
@EnableScheduling
@EnableConfigurationProperties(RecurringSettlementProperties.class)
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementBatchConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "poll-enabled", havingValue = "true",
            matchIfMissing = true)
    RecurringSettlementScheduler recurringSettlementPolling(DispatchRecurringSettlementsUseCase dispatcher,
            RecurringSettlementProperties properties) {
        return new RecurringSettlementScheduler(dispatcher, properties);
    }

    @Bean
    Job recurringSettlementRequestJob(JobRepository repository, DataSource dataSource,
            ProcessRecurringSettlementUseCase worker, RecordRecurringSettlementFailureUseCase failures,
            Environment environment) {
        if (environment.getProperty("spring.batch.job.enabled", Boolean.class, true)) {
            throw new IllegalStateException("Recurring jobs require spring.batch.job.enabled=false");
        }
        var noOuterTransaction = new DefaultTransactionAttribute(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        var step = new StepBuilder("recurringSettlement.publish", repository)
                .tasklet(new RecurringSettlementTasklet(worker, failures), new JdbcTransactionManager(dataSource))
                .transactionAttribute(noOuterTransaction).build();
        return new JobBuilder("recurringSettlementRequestJob", repository).start(step)
                .listener(new JobExecutionListener() {
                    @Override
                    public void beforeJob(JobExecution execution) {
                        boolean automatic = !"MANUAL".equals(execution.getJobParameters().getString("trigger"));
                        execution.getExecutionContext().put("automatic", automatic);
                        repository.updateExecutionContext(execution);
                        log.info("Recurring execution started. recurringId={}, scheduledFor={}, jobExecutionId={},"
                                        + " processId={}, automatic={}",
                                execution.getJobParameters().getString("recurringSettlementId"),
                                execution.getJobParameters().getString("scheduledFor"), execution.getId(),
                                ProcessHandle.current().pid(), automatic);
                    }
                }).build();
    }

    @Bean
    ThreadPoolTaskScheduler recurringSettlementScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("recurring-settlement-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }
}
