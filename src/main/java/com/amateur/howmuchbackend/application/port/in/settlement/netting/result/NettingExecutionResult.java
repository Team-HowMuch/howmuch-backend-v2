package com.amateur.howmuchbackend.application.port.in.settlement.netting.result;

import java.util.UUID;

public record NettingExecutionResult(
        UUID executionId, UUID nettingId, String status, String errorCode) {}
