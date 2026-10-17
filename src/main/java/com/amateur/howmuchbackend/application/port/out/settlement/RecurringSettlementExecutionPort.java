package com.amateur.howmuchbackend.application.port.out.settlement;

import java.time.LocalDateTime;
import java.util.UUID;

/** Batch 실행·상태 조회 경계. Dispatcher가 Spring Batch 타입을 직접 참조하지 않게 한다. */
public interface RecurringSettlementExecutionPort {
    Execution inspect(UUID id, LocalDateTime scheduledFor);

    LaunchResult launch(UUID id, LocalDateTime scheduledFor, Mode mode, int maximumAutomaticAttempts);

    enum State { NEW, RUNNING, FAILED, STOPPED, COMPLETED, UNKNOWN }

    enum Mode { AUTOMATIC, MANUAL, RECOVER_MANUAL }

    enum LaunchResult { LAUNCHED, SKIPPED, TRANSIENT_FAILURE, UNEXPECTED_FAILURE }

    record Execution(State state, int automaticAttempts, boolean lastAttemptAutomatic, boolean transientFailure,
                     LocalDateTime lastAttemptAt) {
    }
}
