package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.port.in.user.command.DeleteMyAccountCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteMyAccountServiceTest {
    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private UserAccountDeletionExecutor deletionExecutor;

    @Test
    void pinMatchInvokesSharedDeletionFlow() {
        User user = user();
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(2L));
        when(loadUserPort.findByIdForUpdate(2L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("222222", user.pinHash())).thenReturn(true);

        service().delete(new DeleteMyAccountCommand("222222"));

        verify(deletionExecutor).delete(org.mockito.ArgumentMatchers.eq(user), any(LocalDateTime.class));
    }

    @Test
    void pinMismatchDoesNotInvokeSharedDeletionFlow() {
        User user = user();
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(2L));
        when(loadUserPort.findByIdForUpdate(2L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service().delete(new DeleteMyAccountCommand("222222")))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(UserStatusCode.USER_DELETION_PIN_MISMATCH));
        verifyNoInteractions(deletionExecutor);
    }

    private DeleteMyAccountService service() {
        return new DeleteMyAccountService(currentActorPort, loadUserPort,
                new PinPolicy(5, Duration.ofMinutes(30)), passwordEncoder, deletionExecutor);
    }

    private User user() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);
        return new User(2L, "identity", "테스트 사용자", "testuser", null, null,
                "01012345678", "pin-hash", null, null, true, UserRole.USER, true,
                null, null, null, now, now);
    }
}
