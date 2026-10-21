package com.amateur.howmuchbackend.application.settlement.netting;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.GroupNettingResult.ReminderBlockedReason;
import com.amateur.howmuchbackend.application.settlement.netting.policy.NettedTransferReminderPolicy;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingStatusCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class NettedTransferReminderPolicyTest {
    private final NettedTransferReminderPolicy policy = new NettedTransferReminderPolicy();

    @ParameterizedTest
    @CsvSource(value = {"ACTIVE,0,0,ALLOWED", "ACTIVE,0,1,DEPOSIT_RECEIVED",
            "ACTIVE,0,1000,DEPOSIT_RECEIVED", "ACTIVE,0,-1,DEPOSIT_RECEIVED",
            "ACTIVE,1,0,DEPOSIT_REVIEW_OPEN", "ACTIVE,2,1000,DEPOSIT_REVIEW_OPEN",
            "COMPLETED,0,0,NETTING_NOT_ACTIVE", "SUPERSEDED,2,1000,NETTING_NOT_ACTIVE"},
            nullValues = "ALLOWED")
    void blockedReason_preservesExistingConditionsAndPrecedence(String status, int reviews,
            long confirmed, ReminderBlockedReason expected) {
        assertThat(policy.blockedReason(status, reviews, confirmed)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"NETTING_NOT_ACTIVE,NETTING_REMINDER_NOT_ACTIVE",
            "DEPOSIT_REVIEW_OPEN,NETTING_REMINDER_DEPOSIT_REVIEW_OPEN",
            "DEPOSIT_RECEIVED,NETTING_REMINDER_DEPOSIT_RECEIVED"})
    void rejectionCode_usesNettingSpecificConflictMessage(ReminderBlockedReason reason, NettingStatusCode code) {
        assertThat(policy.rejectionCode(reason)).isEqualTo(code);
        assertThat(code.getStatus()).isEqualTo(409);
        assertThat(code.getMessage()).contains("압축").doesNotContain("품목 선택");
    }
}
