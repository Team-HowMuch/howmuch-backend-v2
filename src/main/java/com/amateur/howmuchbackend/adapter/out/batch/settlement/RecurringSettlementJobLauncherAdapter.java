package com.amateur.howmuchbackend.adapter.out.batch.settlement;

import com.amateur.howmuchbackend.adapter.in.batch.settlement.RecurringSettlementFailureClassifier;
import com.amateur.howmuchbackend.application.port.out.settlement.RecurringSettlementExecutionPort;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.launch.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Spring Batch 실행·상태 경계 구현. 같은 회차 시작은 PostgreSQL session advisory lock으로 직렬화하고,
 * 잠금 안에서 자동 시도 상한을 다시 확인해 4회째 자동 실행을 막는다. 업무 트랜잭션의 행 잠금·UNIQUE와는
 * 별개다.
 */
@Component
@Slf4j
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementJobLauncherAdapter implements RecurringSettlementExecutionPort {
    private static final int LOCK_NAMESPACE = 0x52454355;

    private final JobRepository repository;
    private final JobOperator operator;
    private final Job job;
    private final DataSource dataSource;

    public RecurringSettlementJobLauncherAdapter(JobRepository repository, JobOperator operator,
            @Qualifier("recurringSettlementRequestJob") Job job, DataSource dataSource) {
        this.repository = repository;
        this.operator = operator;
        this.job = job;
        this.dataSource = dataSource;
    }

    @Override
    public Execution inspect(UUID id, LocalDateTime scheduledFor) {
        try {
            var history = history(parameters(id, scheduledFor, Mode.AUTOMATIC));
            if (history.isEmpty()) {
                return new Execution(State.NEW, 0, true, false, null);
            }
            var latest = history.getLast();
            int automaticAttempts = (int) history.stream().filter(this::automatic).count();
            State state = latest.isRunning() ? State.RUNNING : switch (latest.getStatus()) {
                case COMPLETED -> State.COMPLETED;
                case FAILED -> State.FAILED;
                case STOPPED -> State.STOPPED;
                default -> State.UNKNOWN;
            };
            boolean transientFailure = latest.getExitStatus().getExitDescription() != null
                    && latest.getExitStatus().getExitDescription().contains(RecurringSettlement.TRANSIENT_FAILURE);
            return new Execution(state, automaticAttempts, automatic(latest), transientFailure,
                    latest.getEndTime() == null ? latest.getCreateTime() : latest.getEndTime());
        } catch (RuntimeException unavailable) {
            log.warn("Recurring Batch metadata unavailable. recurringId={}, scheduledFor={}", id, scheduledFor);
            return new Execution(State.UNKNOWN, 0, true, false, null);
        }
    }

    @Override
    public LaunchResult launch(UUID id, LocalDateTime scheduledFor, Mode mode, int maximumAutomaticAttempts) {
        // Serialize the metadata recheck and synchronous launch across app instances.
        // This session lock does not extend the Application's business transaction.
        int lockKey = (id + ":" + scheduledFor.toInstant(ZoneOffset.UTC)).hashCode();
        try (Connection lockConnection = dataSource.getConnection()) {
            if (!advisory(lockConnection, "pg_try_advisory_lock", lockKey)) {
                return LaunchResult.SKIPPED;
            }
            try {
                return launchLocked(parameters(id, scheduledFor, mode), mode, maximumAutomaticAttempts);
            } finally {
                try {
                    if (!advisory(lockConnection, "pg_advisory_unlock", lockKey)) {
                        lockConnection.abort(Runnable::run);
                    }
                } catch (SQLException releaseFailure) {
                    // Never return a pooled session that might still own an advisory lock.
                    lockConnection.abort(Runnable::run);
                    throw releaseFailure;
                }
            }
        } catch (JobExecutionAlreadyRunningException | JobInstanceAlreadyCompleteException contention) {
            return LaunchResult.SKIPPED;
        } catch (Exception failure) {
            log.warn("Recurring Batch launch failed. recurringId={}, scheduledFor={}", id, scheduledFor);
            return RecurringSettlementFailureClassifier.transientDatabaseFailure(failure)
                    ? LaunchResult.TRANSIENT_FAILURE : LaunchResult.UNEXPECTED_FAILURE;
        }
    }

    private LaunchResult launchLocked(JobParameters parameters, Mode mode, int maximumAutomaticAttempts)
            throws Exception {
        var history = history(parameters);
        if (mode == Mode.AUTOMATIC && history.stream().filter(this::automatic).count() >= maximumAutomaticAttempts) {
            return LaunchResult.SKIPPED;
        }
        if (history.isEmpty()) {
            operator.start(job, parameters);
        } else {
            var latest = history.getLast();
            if (latest.getStatus() == BatchStatus.COMPLETED) {
                return LaunchResult.SKIPPED;
            }
            if (latest.isRunning()) {
                if (mode != Mode.RECOVER_MANUAL) {
                    return LaunchResult.SKIPPED;
                }
                latest = operator.recover(latest);
            }
            if (latest.getStatus() != BatchStatus.FAILED
                    && !(latest.getStatus() == BatchStatus.STOPPED && mode != Mode.AUTOMATIC)) {
                return LaunchResult.SKIPPED;
            }
            if (mode == Mode.AUTOMATIC) {
                operator.restart(latest);
            } else {
                // Preserve JobInstance identity while persisting this attempt's non-identifying mode.
                operator.start(job, parameters);
            }
        }
        return LaunchResult.LAUNCHED;
    }

    private boolean advisory(Connection connection, String function, int key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT " + function + "(?, ?)")) {
            statement.setInt(1, LOCK_NAMESPACE);
            statement.setInt(2, key);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private List<JobExecution> history(JobParameters parameters) {
        var instance = repository.getJobInstance(job.getName(), parameters);
        if (instance == null) {
            return List.of();
        }
        return repository.getJobExecutions(instance).stream()
                .sorted(Comparator.comparing(JobExecution::getId)).toList();
    }

    private boolean automatic(JobExecution execution) {
        // JobParameters are persisted when the execution is created, before any Tasklet/listener.
        return !"MANUAL".equals(execution.getJobParameters().getString("trigger"));
    }

    private JobParameters parameters(UUID id, LocalDateTime scheduledFor, Mode mode) {
        return new JobParametersBuilder().addString("recurringSettlementId", id.toString())
                .addString("scheduledFor", scheduledFor.toInstant(ZoneOffset.UTC).toString())
                .addString("trigger", mode == Mode.AUTOMATIC ? "AUTOMATIC" : "MANUAL", false).toJobParameters();
    }
}
