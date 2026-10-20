package com.amateur.howmuchbackend.application.port.in.settlement.netting;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.NettingExecutionResult;

import java.util.UUID;

public interface ExecuteGroupNettingUseCase {
    NettingExecutionResult execute(
            long groupId,
            com.amateur.howmuchbackend.application.port.in.settlement.netting.command
                            .ExecuteGroupNettingCommand
                    command);

    NettingExecutionResult getExecution(long groupId, UUID executionId);
}
