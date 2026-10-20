package com.amateur.howmuchbackend.application.settlement.netting.policy;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingExecutionStorePort.Status;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;

import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class NettingExecutionFailurePolicy {
    private static final Set<String> STALE_CODES =
            Set.of(
                    NettingStatusCode.NETTING_INPUT_CHANGED.getCode(),
                    NettingStatusCode.NETTING_NO_ELIGIBLE_DEBTS.getCode(),
                    NettingStatusCode.NETTING_INSUFFICIENT_SETTLEMENTS.getCode(),
                    NettingStatusCode.NETTING_INVALID_SOURCE.getCode(),
                    NettingStatusCode.NETTING_ALREADY_ACTIVE.getCode());
    private static final Set<String> RETRY_CODES =
            Set.of(
                    "NETTING_WORKER_FAILED",
                    ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE.getCode(),
                    ExternalConnectionStatusCode.CODEF_RATE_LIMITED.getCode(),
                    ExternalConnectionStatusCode.CODEF_RESPONSE_INVALID.getCode(),
                    ExternalConnectionStatusCode.CODEF_UNMAPPED_ERROR.getCode(),
                    CommonStatusCode.TEMPORARILY_UNAVAILABLE.getCode(),
                    CommonStatusCode.TOO_MANY_REQUEST.getCode(),
                    CommonStatusCode.CONCURRENT_UPDATE.getCode());

    public Status statusFor(String code) {
        if (code == null) return Status.FAILED;
        if (STALE_CODES.contains(code)) return Status.STALE;
        if (RETRY_CODES.contains(code)) return Status.RETRY;
        return Status.FAILED;
    }
}
