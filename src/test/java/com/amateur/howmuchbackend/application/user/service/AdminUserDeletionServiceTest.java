package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.auth.policy.IdentityPolicy;
import com.amateur.howmuchbackend.application.port.in.user.command.SearchAdminUserCommand;
import com.amateur.howmuchbackend.application.port.out.auth.AuthAuditPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminUserDeletionServiceTest {
    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private IdentityPolicy identityPolicy;
    @Mock private UserAccountDeletionExecutor deletionExecutor;
    @Mock private AuthAuditPort authAuditPort;

    @Test
    void nonAdminCannotSearchOrDelete() {
        givenActor(user(1L, UserRole.USER, true));

        assertForbidden(() -> service().search(new SearchAdminUserCommand("010-1234-5678")));
        assertForbidden(() -> service().delete(2L));
        verifyNoInteractions(identityPolicy, deletionExecutor, authAuditPort);
        verify(loadUserPort, never()).findByPhoneNumber(any());
        verify(loadUserPort, never()).findByIdForUpdate(any());
    }

    @Test
    void inactiveAdminCannotSearch() {
        givenActor(user(1L, UserRole.ADMIN, false));

        assertForbidden(() -> service().search(new SearchAdminUserCommand("010-1234-5678")));
        verifyNoInteractions(identityPolicy);
    }

    @Test
    void searchNormalizesPhoneAndReturnsOnlyMinimalIdentity() {
        givenActor(user(1L, UserRole.ADMIN, true));
        when(identityPolicy.normalizePhoneNumber("010-1234-5678")).thenReturn("01012345678");
        when(loadUserPort.findByPhoneNumber("01012345678"))
                .thenReturn(Optional.of(user(2L, UserRole.USER, true)));

        var result = service().search(new SearchAdminUserCommand("010-1234-5678"));

        assertThat(result.userId()).isEqualTo(2L);
        assertThat(result.name()).isEqualTo("테스트 사용자");
        assertThat(result.toString()).doesNotContain("01012345678");
    }

    @Test
    void searchHidesInactiveAndAdminTargets() {
        givenActor(user(1L, UserRole.ADMIN, true));
        when(identityPolicy.normalizePhoneNumber("phone")).thenReturn("normalized");
        when(loadUserPort.findByPhoneNumber("normalized"))
                .thenReturn(Optional.of(user(2L, UserRole.USER, false)))
                .thenReturn(Optional.of(user(3L, UserRole.ADMIN, true)));

        assertNotFound(() -> service().search(new SearchAdminUserCommand("phone")));
        assertNotFound(() -> service().search(new SearchAdminUserCommand("phone")));
    }

    @Test
    void deleteRunsSharedFlowBeforeAudit() {
        givenActor(user(1L, UserRole.ADMIN, true));
        User target = user(2L, UserRole.USER, true);
        when(loadUserPort.findByIdForUpdate(2L)).thenReturn(Optional.of(target));

        service().delete(2L);

        InOrder order = inOrder(deletionExecutor, authAuditPort);
        order.verify(deletionExecutor).delete(eq(target), any(LocalDateTime.class));
        order.verify(authAuditPort).recordAdminUserDeletion(eq(1L), eq(2L), any(LocalDateTime.class));
    }

    @Test
    void deleteRejectsAdminAndRepeatedDeletionWithoutAudit() {
        givenActor(user(1L, UserRole.ADMIN, true));
        when(loadUserPort.findByIdForUpdate(2L))
                .thenReturn(Optional.of(user(2L, UserRole.ADMIN, true)))
                .thenReturn(Optional.of(user(2L, UserRole.USER, false)));

        assertForbidden(() -> service().delete(2L));
        assertNotFound(() -> service().delete(2L));
        verifyNoInteractions(deletionExecutor, authAuditPort);
    }

    private void givenActor(User actor) {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(actor.id()));
        when(loadUserPort.findById(actor.id())).thenReturn(Optional.of(actor));
    }

    private User user(Long id, UserRole role, boolean active) {
        LocalDateTime now = LocalDateTime.of(2026, 9, 28, 12, 0);
        return new User(id, "identity-" + id, "테스트 사용자", "user" + id,
                null, null, "01012345678", "pin-hash", null, null, true, role, active,
                null, null, null, now, now);
    }

    private AdminUserDeletionService service() {
        return new AdminUserDeletionService(currentActorPort, loadUserPort, identityPolicy,
                deletionExecutor, authAuditPort);
    }

    private void assertForbidden(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(CommonStatusCode.FORBIDDEN));
    }

    private void assertNotFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApplicationException.class,
                exception -> assertThat(exception.getStatusCode()).isEqualTo(AuthStatusCode.USER_NOT_FOUND));
    }
}
