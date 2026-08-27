package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.user.UpdateMyProfileUseCase;
import com.amateur.howmuchbackend.application.port.in.user.command.UpdateMyProfileCommand;
import com.amateur.howmuchbackend.application.port.in.user.result.MyProfileResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.SaveUserPort;
import com.amateur.howmuchbackend.application.user.model.ProfileImageUpload;
import com.amateur.howmuchbackend.application.user.policy.ProfileImagePolicy;
import com.amateur.howmuchbackend.application.user.policy.PublicIdPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.status.UserStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class UpdateMyProfileService implements UpdateMyProfileUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final SaveUserPort saveUserPort;
    private final ProfileImageStoragePort profileImageStoragePort;
    private final PublicIdPolicy publicIdPolicy;
    private final ProfileImagePolicy profileImagePolicy;

    @Override
    @Transactional
    public MyProfileResult update(UpdateMyProfileCommand command) {
        if (command.resetAvatar() && command.avatarContent() != null) {
            throw ApplicationException.of(
                    com.amateur.howmuchbackend.common.exception.status.CommonStatusCode.INVALID_ARGUMENT,
                    "아바타 업로드와 기본 이미지 복원을 동시에 요청할 수 없습니다."
            );
        }

        CurrentActor actor = currentActorPort.getCurrentActor();
        User user = loadUserPort.findByIdForUpdate(actor.userId())
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));

        String publicId = command.publicId() == null
                ? user.publicId()
                : publicIdPolicy.normalize(command.publicId());
        if (!publicId.equals(user.publicId()) && loadUserPort.existsByPublicId(publicId)) {
            throw ApplicationException.of(UserStatusCode.PUBLIC_ID_ALREADY_EXISTS);
        }
        String previousAvatarFileName = user.avatarFileName();
        String avatarFileName = previousAvatarFileName;
        String newlyStoredFileName = null;

        if (command.avatarContent() != null) {
            ProfileImageUpload image = profileImagePolicy.validate(command.avatarContent());
            newlyStoredFileName = profileImageStoragePort.save(image);
            avatarFileName = newlyStoredFileName;
        } else if (command.resetAvatar()) {
            avatarFileName = null;
        }

        if (publicId.equals(user.publicId()) && equalsNullable(avatarFileName, previousAvatarFileName)) {
            return MyProfileResult.from(user);
        }

        User updated;
        try {
            updated = saveUserPort.update(user.changeProfile(
                    publicId,
                    avatarFileName,
                    LocalDateTime.now(ZoneOffset.UTC)
            ));
        } catch (RuntimeException exception) {
            if (newlyStoredFileName != null) {
                profileImageStoragePort.delete(newlyStoredFileName);
            }
            throw exception;
        }

        scheduleAvatarCleanup(previousAvatarFileName, avatarFileName, newlyStoredFileName);
        return MyProfileResult.from(updated);
    }

    private void scheduleAvatarCleanup(
            String previousAvatarFileName,
            String avatarFileName,
            String newlyStoredFileName
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            cleanupAfterCommit(previousAvatarFileName, avatarFileName);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cleanupAfterCommit(previousAvatarFileName, avatarFileName);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED && newlyStoredFileName != null) {
                    profileImageStoragePort.delete(newlyStoredFileName);
                }
            }
        });
    }

    private void cleanupAfterCommit(String previousAvatarFileName, String avatarFileName) {
        if (previousAvatarFileName != null && !previousAvatarFileName.equals(avatarFileName)) {
            profileImageStoragePort.delete(previousAvatarFileName);
        }
    }

    private boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }
}
