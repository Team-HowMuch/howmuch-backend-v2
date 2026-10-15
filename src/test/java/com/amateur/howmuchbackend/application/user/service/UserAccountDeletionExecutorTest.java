package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadUserSettlementStatePort;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.user.SaveUserPort;
import com.amateur.howmuchbackend.application.port.out.user.UserPushTargetStorePort;
import com.amateur.howmuchbackend.application.user.policy.ConservativeUserDeletionPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.group.Group;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupMemberStatus;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAccountDeletionExecutorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 12, 0);
    @Mock private SaveUserPort saveUserPort;
    @Mock private LoadGroupPort loadGroupPort;
    @Mock private LoadGroupMemberPort loadGroupMemberPort;
    @Mock private SaveGroupMemberPort saveGroupMemberPort;
    @Mock private LoadUserSettlementStatePort settlementStatePort;
    @Mock private AuthSessionPort authSessionPort;
    @Mock private UserPushTargetStorePort pushTargetStorePort;
    @Mock private ExternalConnectionStorePort externalConnectionStorePort;
    @Mock private ProfileImageStoragePort imageStoragePort;
    @Mock private com.amateur.howmuchbackend.application.port.out.recap.DeleteOwnRecapsPort deleteOwnRecapsPort;
    @Mock private PasswordEncoder passwordEncoder;

    @Test
    void successfulDeletionLeavesMembershipAndAnonymizesUser() {
        User user = user(null);
        GroupMember member = GroupMember.member(9L, user.id(), NOW.minusDays(1));
        when(loadGroupMemberPort.findActiveByUserId(user.id())).thenReturn(List.of(member));
        when(loadGroupPort.findByIdForUpdate(9L)).thenReturn(Optional.of(group()));
        when(loadGroupMemberPort.findByGroupIdAndUserIdForUpdate(9L, user.id()))
                .thenReturn(Optional.of(member));
        when(passwordEncoder.encode(anyString())).thenReturn("new-pin-hash");

        executor().delete(user, NOW);

        ArgumentCaptor<GroupMember> memberCaptor = ArgumentCaptor.forClass(GroupMember.class);
        verify(saveGroupMemberPort).update(memberCaptor.capture());
        assertThat(memberCaptor.getValue().status()).isEqualTo(GroupMemberStatus.LEFT);
        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(saveUserPort).update(userCaptor.capture());
        assertThat(userCaptor.getValue().active()).isFalse();
        assertThat(userCaptor.getValue().phoneNumber()).isNotEqualTo(user.phoneNumber());
        assertThat(userCaptor.getValue().pinHash()).isEqualTo("new-pin-hash");
        verify(deleteOwnRecapsPort).deleteOwnedRecaps(user.id(), NOW);
        verify(pushTargetStorePort).clear(user.id(), NOW);
        verify(externalConnectionStorePort).clearCredentialsByUserId(user.id());
        verify(authSessionPort).deleteByUserId(user.id());
    }

    @Test
    void ownerAndIncompleteSettlementBlockWithoutMutation() {
        User user = user(null);
        GroupMember owner = GroupMember.owner(9L, user.id(), NOW.minusDays(1));
        when(loadGroupMemberPort.findActiveByUserId(user.id())).thenReturn(List.of(owner), List.of());
        when(loadGroupPort.findByIdForUpdate(9L)).thenReturn(Optional.of(group()));
        when(loadGroupMemberPort.findByGroupIdAndUserIdForUpdate(9L, user.id()))
                .thenReturn(Optional.of(owner));
        when(settlementStatePort.existsByUserIdAndStatusNotCompleted(user.id()))
                .thenReturn(false, true);

        assertThatThrownBy(() -> executor().delete(user, NOW))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(UserStatusCode.USER_DELETION_GROUP_OWNER_TRANSFER_REQUIRED));
        assertThatThrownBy(() -> executor().delete(user, NOW))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(UserStatusCode.USER_DELETION_BLOCKED_BY_SETTLEMENT));
        verify(saveUserPort, never()).update(org.mockito.ArgumentMatchers.any());
        verify(deleteOwnRecapsPort, never()).deleteOwnedRecaps(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(externalConnectionStorePort, never()).clearCredentialsByUserId(user.id());
        verify(authSessionPort, never()).deleteByUserId(user.id());
    }

    @Test
    void sessionAndAvatarAreDeletedOnlyAfterCommit() {
        User user = user("avatar.png");
        when(loadGroupMemberPort.findActiveByUserId(user.id())).thenReturn(List.of());
        when(passwordEncoder.encode(anyString())).thenReturn("new-pin-hash");
        TransactionSynchronizationManager.initSynchronization();
        try {
            executor().delete(user, NOW);
            verify(authSessionPort, never()).deleteByUserId(user.id());
            verify(imageStoragePort, never()).delete("avatar.png");
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCommit());
            verify(authSessionPort).deleteByUserId(user.id());
            verify(imageStoragePort).delete("avatar.png");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private UserAccountDeletionExecutor executor() {
        return new UserAccountDeletionExecutor(saveUserPort, loadGroupPort, loadGroupMemberPort,
                saveGroupMemberPort, settlementStatePort, authSessionPort, pushTargetStorePort,
                externalConnectionStorePort, imageStoragePort, deleteOwnRecapsPort, passwordEncoder, new ConservativeUserDeletionPolicy());
    }

    private Group group() {
        return Group.create("테스트 그룹", null, null, 3L, "ABCDEFGH", NOW.minusDays(2));
    }

    private User user(String avatar) {
        return new User(2L, "identity", "테스트 사용자", "testuser", null, null,
                "01012345678", "old-pin-hash", null, avatar, true, UserRole.USER, true,
                null, null, null, NOW.minusDays(3), NOW.minusDays(3));
    }
}
