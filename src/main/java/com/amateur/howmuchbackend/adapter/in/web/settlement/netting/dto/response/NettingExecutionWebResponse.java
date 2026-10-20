package com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.NettingExecutionResult;

import java.util.UUID;

public record NettingExecutionWebResponse(
        UUID executionId, UUID nettingId, String status, String errorCode) {
    public static NettingExecutionWebResponse from(NettingExecutionResult r) {
        return new NettingExecutionWebResponse(
                r.executionId(), r.nettingId(), r.status(), r.errorCode());
    }
}
