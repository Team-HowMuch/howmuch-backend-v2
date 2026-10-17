package com.amateur.howmuchbackend.application.port.in.settlement.netting;

import java.util.UUID;

public interface ProcessNettingExecutionUseCase {
    com.amateur.howmuchbackend.application.port.in.settlement.netting.result
                    .NettingSynchronizationResult
            synchronize(UUID executionId, UUID leaseToken);

    void commit(
            com.amateur.howmuchbackend.application.port.in.settlement.netting.command
                            .CommitNettingExecutionCommand
                    command);

    void failed(UUID executionId, UUID leaseToken, String code);
}
