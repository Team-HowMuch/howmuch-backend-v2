package com.amateur.howmuchbackend.adapter.in.batch.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.ProcessRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RecordRecurringSettlementFailureUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.RecordRecurringSettlementFailureCommand;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

/** JobParameters를 Application 값으로 바꿔 UseCase를 호출하는 얇은 어댑터. */
@Slf4j
@RequiredArgsConstructor
public class RecurringSettlementTasklet implements Tasklet {
    private final ProcessRecurringSettlementUseCase worker;
    private final RecordRecurringSettlementFailureUseCase failures;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext context) {
        var execution = contribution.getStepExecution().getJobExecution();
        var parameters = execution.getJobParameters();
        UUID id;
        LocalDateTime scheduledFor;
        try {
            id = UUID.fromString(parameters.getString("recurringSettlementId"));
            scheduledFor = LocalDateTime.ofInstant(Instant.parse(parameters.getString("scheduledFor")), ZoneOffset.UTC);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("INVALID_RECURRING_JOB_PARAMETERS");
        }
        boolean automatic = !"MANUAL".equals(parameters.getString("trigger"));
        try {
            var result = worker.process(id, scheduledFor);
            contribution.setExitStatus(new ExitStatus(result.name()));
            return RepeatStatus.FINISHED;
        } catch (RuntimeException failure) {
            boolean transientFailure = RecurringSettlementFailureClassifier.transientDatabaseFailure(failure);
            try {
                failures.record(new RecordRecurringSettlementFailureCommand(id, scheduledFor, transientFailure,
                        automatic));
            } catch (RuntimeException recordingFailure) {
                log.warn("Recurring failure summary unavailable. recurringId={}, scheduledFor={}, jobExecutionId={}",
                        id, scheduledFor, execution.getId());
            }
            // Batch persists exception descriptions; never attach the original query or external cause.
            throw new IllegalStateException(transientFailure
                    ? RecurringSettlement.TRANSIENT_FAILURE : RecurringSettlement.UNEXPECTED_FAILURE);
        }
    }
}
