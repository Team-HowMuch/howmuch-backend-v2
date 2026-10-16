package com.amateur.howmuchbackend.application.account.policy;

import static org.assertj.core.api.Assertions.*;

import com.amateur.howmuchbackend.application.port.in.account.command.StartCertificateReferenceConnectionCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateInstitutionResult;
import com.amateur.howmuchbackend.common.exception.ApplicationException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.UUID;

class CertificateReferenceInputPolicyTest {
    final CertificateReferenceInputPolicy policy = new CertificateReferenceInputPolicy();
    final LocalDate today = LocalDate.of(2026, 10, 16);

    StartCertificateReferenceConnectionCommand command(
            String birth, String number, String password) {
        return new StartCertificateReferenceConnectionCommand(
                1, UUID.randomUUID(), "0088", "0020", birth, number, password);
    }

    @Test
    void normalizesAccountFormattingWithoutChangingPassword() {
        var c = policy.normalize(command("900101", "123-456", " 1234 "), today);
        assertThat(c.accountNumber()).isEqualTo("123456");
        assertThat(c.accountPassword()).isEqualTo(" 1234 ");
        assertThat(c.toString()).doesNotContain("900101", "123456", " 1234 ");
    }

    @ParameterizedTest
    @ValueSource(strings = {"19990229", "20270201", "990230", "notdate", "18000101"})
    void invalidDateIsRejected(String birth) {
        assertThatThrownBy(() -> policy.normalize(command(birth, null, null), today))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void missingRequiredInputsAndPartialPairAreRejected() {
        var c = policy.normalize(command(null, null, null), today);
        var rule = new CertificateInstitutionResult("0020", "우리은행", true, true, null, 3);
        assertThatThrownBy(() -> policy.validateRequirements(c, rule))
                .isInstanceOf(ApplicationException.class);
        assertThatThrownBy(() -> policy.normalize(command(null, "123", null), today))
                .isInstanceOf(ApplicationException.class);
    }

    @Test
    void leapDayAndOptionalInputsAreAccepted() {
        assertThat(policy.normalize(command("000229", null, null), today).birthDate())
                .isEqualTo("000229");
        var c = policy.normalize(command(null, null, null), today);
        policy.validateRequirements(
                c, new CertificateInstitutionResult("0088", "신한은행", true, false, null, 1));
    }
}
