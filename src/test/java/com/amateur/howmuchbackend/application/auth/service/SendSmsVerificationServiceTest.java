package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.auth.policy.SmsVerificationPolicy;
import com.amateur.howmuchbackend.application.port.in.auth.command.SendSmsVerificationCommand;
import com.amateur.howmuchbackend.application.port.out.auth.SmsSenderPort;
import com.amateur.howmuchbackend.application.port.out.auth.SmsVerificationStorePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SendSmsVerificationServiceTest {
    private static final String PHONE_NUMBER = "01012345678";

    @Mock
    private SmsVerificationStorePort smsVerificationStorePort;

    @Mock
    private SmsSenderPort smsSenderPort;

    @Mock
    private LoadUserPort loadUserPort;

    private SendSmsVerificationService service;

    @BeforeEach
    void setUp() {
        service = new SendSmsVerificationService(
                new IdentityPolicy("test-identity-hmac-secret"),
                new SmsVerificationPolicy(Duration.ofMinutes(3), Duration.ofMinutes(30), Duration.ofSeconds(60), 5),
                smsVerificationStorePort,
                smsSenderPort,
                loadUserPort
        );
    }

    @Test
    void smsVerification_registeredPhone_rejectedBeforeReservation() {
        when(loadUserPort.findByPhoneNumber(PHONE_NUMBER)).thenReturn(Optional.of(mock(User.class)));

        assertThatThrownBy(() -> service.send(new SendSmsVerificationCommand("010-1234-5678")))
                .isInstanceOfSatisfying(ApplicationException.class,
                        exception -> org.assertj.core.api.Assertions.assertThat(exception.getStatusCode())
                                .isEqualTo(AuthStatusCode.PHONE_NUMBER_ALREADY_REGISTERED));

        verify(loadUserPort).findByPhoneNumber(PHONE_NUMBER);
        verifyNoInteractions(smsVerificationStorePort, smsSenderPort);
    }

    @Test
    void smsVerification_unregisteredPhone_sendsOtp() {
        when(loadUserPort.findByPhoneNumber(PHONE_NUMBER)).thenReturn(Optional.empty());
        when(smsVerificationStorePort.tryReserve(any(), any(), any(), any())).thenReturn(true);

        service.send(new SendSmsVerificationCommand(PHONE_NUMBER));

        verify(loadUserPort).findByPhoneNumber(PHONE_NUMBER);
        verify(smsVerificationStorePort).tryReserve(any(), any(), any(), any());
        verify(smsSenderPort).send(eq(PHONE_NUMBER), anyString());
    }
}
