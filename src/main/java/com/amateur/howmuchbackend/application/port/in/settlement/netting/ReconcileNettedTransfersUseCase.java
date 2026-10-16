package com.amateur.howmuchbackend.application.port.in.settlement.netting;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.NettingExecutionResult;

import java.util.UUID;

public interface ReconcileNettedTransfersUseCase {
    NettingExecutionResult reconcile(long groupId, UUID nettingId, UUID idempotencyKey);
}
