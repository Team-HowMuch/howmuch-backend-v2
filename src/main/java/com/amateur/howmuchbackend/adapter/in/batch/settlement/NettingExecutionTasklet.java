package com.amateur.howmuchbackend.adapter.in.batch.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.ProcessNettingExecutionUseCase;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingInput;
import com.amateur.howmuchbackend.common.exception.ApplicationException;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;

import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

public class NettingExecutionTasklet implements Tasklet {
    private final ProcessNettingExecutionUseCase worker;
    private final boolean synchronization;
    private final ObjectMapper mapper;

    public NettingExecutionTasklet(
            ProcessNettingExecutionUseCase worker, boolean synchronization, ObjectMapper mapper) {
        this.worker = worker;
        this.synchronization = synchronization;
        this.mapper = mapper;
    }

    public RepeatStatus execute(StepContribution contribution, ChunkContext context) {
        var step = contribution.getStepExecution();
        var parameters = step.getJobParameters();
        UUID id = UUID.fromString(parameters.getString("executionId")),
                token = UUID.fromString(parameters.getString("leaseToken"));
        var state = step.getJobExecution().getExecutionContext();
        try {
            if (synchronization) {
                var input = worker.synchronize(id, token).snapshot();
                if (input != null)
                    state.putString("nettingInput", mapper.writeValueAsString(input));
            } else
                worker.commit(
                        new com.amateur.howmuchbackend.application.port.in.settlement.netting
                                .command.CommitNettingExecutionCommand(
                                id,
                                token,
                                state.containsKey("nettingInput")
                                        ? mapper.readValue(
                                                state.getString("nettingInput"), NettingInput.class)
                                        : null));
            return RepeatStatus.FINISHED;
        } catch (Exception failure) {
            String code =
                    failure instanceof ApplicationException app
                            ? app.getStatusCode().getCode()
                            : "NETTING_WORKER_FAILED";
            worker.failed(id, token, code);
            throw new IllegalStateException("NETTING_EXECUTION_FAILED");
        }
    }
}
