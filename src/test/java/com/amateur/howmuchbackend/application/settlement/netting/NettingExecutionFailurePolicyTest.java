package com.amateur.howmuchbackend.application.settlement.netting;

import static org.assertj.core.api.Assertions.assertThat;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.NettingExecutionStorePort.Status;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettingExecutionFailurePolicy;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class NettingExecutionFailurePolicyTest {
    private final NettingExecutionFailurePolicy policy = new NettingExecutionFailurePolicy();

    @ParameterizedTest
    @ValueSource(
            strings = {
                "CODEF_UPSTREAM_UNAVAILABLE",
                "CODEF_RATE_LIMITED",
                "CODEF_RESPONSE_INVALID",
                "CODEF_UNMAPPED_ERROR",
                "TEMPORARILY_UNAVAILABLE",
                "TOO_MANY_REQUEST",
                "CONCURRENT_UPDATE",
                "NETTING_WORKER_FAILED"
            })
    void temporaryFailuresUseBoundedRetry(String code) {
        assertThat(policy.statusFor(code)).isEqualTo(Status.RETRY);
    }

    @ParameterizedTest
    @EnumSource(
            value = ExternalConnectionStatusCode.class,
            names = {
                "CODEF_UPSTREAM_UNAVAILABLE",
                "CODEF_RATE_LIMITED",
                "CODEF_RESPONSE_INVALID",
                "CODEF_UNMAPPED_ERROR"
            },
            mode = EnumSource.Mode.EXCLUDE)
    void credentialsUserActionAndConfigurationErrorsAreTerminal(ExternalConnectionStatusCode code) {
        assertThat(policy.statusFor(code.getCode())).isEqualTo(Status.FAILED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "NETTING_INPUT_CHANGED",
                "NETTING_NO_ELIGIBLE_DEBTS",
                "NETTING_INSUFFICIENT_SETTLEMENTS",
                "NETTING_INVALID_SOURCE",
                "NETTING_ALREADY_ACTIVE"
            })
    void changedInputsRemainStale(String code) {
        assertThat(policy.statusFor(code)).isEqualTo(Status.STALE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {"GROUP_ACCESS_DENIED", "NETTING_CALCULATOR_UNAVAILABLE", "UNKNOWN_FAILURE"})
    void unknownOrPermanentFailuresAreNotRetried(String code) {
        assertThat(policy.statusFor(code)).isEqualTo(Status.FAILED);
    }
}
