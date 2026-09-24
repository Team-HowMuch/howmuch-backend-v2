package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.auth.policy.PinPolicy;
import com.amateur.howmuchbackend.application.port.in.user.DeleteMyAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.user.command.DeleteMyAccountCommand;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupPort;
import com.amateur.howmuchbackend.application.port.out.group.LoadGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.group.SaveGroupMemberPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadUserSettlementStatePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.user.SaveUserPort;
import com.amateur.howmuchbackend.application.port.out.user.UserPushTargetStorePort;
import com.amateur.howmuchbackend.application.user.policy.UserDeletionPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.group.GroupMember;
import com.amateur.howmuchbackend.domain.group.GroupStatus;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeleteMyAccountService implements DeleteMyAccountUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final LoadGroupPort loadGroupPort;
    private final LoadGroupMemberPort loadGroupMemberPort;
    private final SaveGroupMemberPort saveGroupMemberPort;
    private final LoadUserSettlementStatePort loadUserSettlementStatePort;
    private final AuthSessionPort authSessionPort;
    private final UserPushTargetStorePort userPushTargetStorePort;
    private final ProfileImageStoragePort profileImageStoragePort;
    private final PinPolicy pinPolicy;
    private final PasswordEncoder passwordEncoder;
    private final UserDeletionPolicy userDeletionPolicy;

    @Override
    @Transactional
    public void delete(DeleteMyAccountCommand command) {
        validatePin(command.pin());

        CurrentActor actor = currentActorPort.getCurrentActor();
        User user = loadUserPort.findByIdForUpdate(actor.userId())
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        if (!passwordEncoder.matches(command.pin(), user.pinHash())) {
            throw ApplicationException.of(UserStatusCode.USER_DELETION_PIN_MISMATCH);
        }

        var activeMembers = lockActiveGroupMembers(user.id());
        UserDeletionPolicy.Decision decision = userDeletionPolicy.evaluate(
                new UserDeletionPolicy.Facts(
                        activeMembers.stream().anyMatch(GroupMember::isOwner),
                        loadUserSettlementStatePort.existsByUserIdAndStatusNotCompleted(user.id())
                )
        );
        if (!decision.isAllowed()) {
            throw deletionBlocked(decision.blockReason());
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        activeMembers.stream()
                .filter(member -> !member.isOwner())
                .map(member -> member.leave(now))
                .forEach(saveGroupMemberPort::update);

        String previousAvatarFileName = user.avatarFileName();
        saveUserPort.update(userDeletionPolicy.anonymize(
                user,
                passwordEncoder.encode(java.util.UUID.randomUUID().toString()),
                now
        ));
        userPushTargetStorePort.clear(user.id(), now);
        authSessionPort.deleteByUserId(user.id());
        scheduleAvatarCleanup(previousAvatarFileName);
    }

    private java.util.List<GroupMember> lockActiveGroupMembers(Long userId) {
        var candidateMembers = loadGroupMemberPort.findActiveByUserId(userId);
        return candidateMembers.stream()
                .map(GroupMember::groupId)
                .distinct()
                .sorted()
                .map(groupId -> loadGroupPort.findByIdForUpdate(groupId)
                        .filter(group -> group.status() == GroupStatus.ACTIVE)
                        .flatMap(group -> loadGroupMemberPort.findByGroupIdAndUserIdForUpdate(groupId, userId))
                        .filter(GroupMember::isActive)
                        .orElse(null))
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private void validatePin(String pin) {
        if (!pinPolicy.isFormatValid(pin)) {
            throw ApplicationException.of(AuthStatusCode.INVALID_PIN_FORMAT);
        }
    }

    private ApplicationException deletionBlocked(UserDeletionPolicy.BlockReason reason) {
        return switch (reason) {
            case GROUP_OWNER_TRANSFER_REQUIRED ->
                    ApplicationException.of(UserStatusCode.USER_DELETION_GROUP_OWNER_TRANSFER_REQUIRED);
            case INCOMPLETE_SETTLEMENT ->
                    ApplicationException.of(UserStatusCode.USER_DELETION_BLOCKED_BY_SETTLEMENT);
            case NONE -> throw new IllegalStateException("허용된 탈퇴를 차단 사유로 변환할 수 없습니다.");
        };
    }

    private void scheduleAvatarCleanup(String avatarFileName) {
        if (avatarFileName == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteSafely(avatarFileName);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteSafely(avatarFileName);
            }
        });
    }

    private void deleteSafely(String fileName) {
        try {
            profileImageStoragePort.delete(fileName);
        } catch (RuntimeException exception) {
            log.atWarn()
                    .addKeyValue("event", "image_cleanup_failed")
                    .addKeyValue("imageDomain", "profile")
                    .addKeyValue("fileName", fileName)
                    .addKeyValue("phase", "account_deletion")
                    .addKeyValue("exceptionType", exception.getClass().getName())
                    .log("Profile image cleanup failed after account deletion");
        }
    }
}
