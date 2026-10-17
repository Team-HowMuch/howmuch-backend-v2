package com.amateur.howmuchbackend.application.settlement.netting.service;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.DispatchNettingExecutionsUseCase;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.*;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettingExecutionFailurePolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class NettingExecutionDispatcher implements DispatchNettingExecutionsUseCase {
    private final NettingExecutionStorePort executions;
    private final NettingJobLauncherPort launcher;
    private final NettingExecutionFailurePolicy failures;
    private final UUID workerId = UUID.randomUUID();
    private final AtomicReference<NettingExecutionStorePort.Execution> running =
            new AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicBoolean dispatching =
            new java.util.concurrent.atomic.AtomicBoolean();

    public void dispatch() {
        if (!dispatching.compareAndSet(false, true)) return;
        try {
            var next = executions.claimNext(workerId, LocalDateTime.now(ZoneOffset.UTC));
            if (next.isEmpty()) return;
            var execution = next.get();
            running.set(execution);
            try {
                launcher.launch(execution.id(), execution.leaseToken());
            } catch (RuntimeException failure) {
                String code =
                        failure instanceof ApplicationException app
                                ? app.getStatusCode().getCode()
                                : "NETTING_WORKER_FAILED";
                executions.finish(
                        execution.id(),
                        execution.leaseToken(),
                        failures.statusFor(code),
                        null,
                        code,
                        LocalDateTime.now(ZoneOffset.UTC));
            } finally {
                running.compareAndSet(execution, null);
            }
        } finally {
            dispatching.set(false);
        }
    }

    public void heartbeat() {
        var execution = running.get();
        if (execution != null)
            executions.heartbeat(
                    execution.id(),
                    execution.leaseToken(),
                    workerId,
                    LocalDateTime.now(ZoneOffset.UTC));
    }
}
