package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.port.in.user.ManageMyPushTargetUseCase;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.application.port.out.user.UserPushTargetStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class ManageMyPushTargetService implements ManageMyPushTargetUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final UserPushTargetStorePort pushTargetStorePort;

    @Override
    @Transactional
    public void register(String firebaseInstallationId) {
        String normalized = firebaseInstallationId == null ? "" : firebaseInstallationId.trim();
        if (normalized.isEmpty() || normalized.length() > User.MAX_FCM_TARGET_LENGTH) {
            throw ApplicationException.of(
                    CommonStatusCode.INVALID_ARGUMENT,
                    "앱 푸시 토큰은 1~255자로 입력해야 합니다."
            );
        }
        Long userId = currentActorPort.getCurrentActor().userId();
        loadUserPort.findByIdForUpdate(userId)
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        if (!pushTargetStorePort.claim(
                userId,
                normalized,
                LocalDateTime.now(ZoneOffset.UTC)
        )) {
            throw ApplicationException.of(AuthStatusCode.USER_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public void clear() {
        Long userId = currentActorPort.getCurrentActor().userId();
        if (!pushTargetStorePort.clear(userId, LocalDateTime.now(ZoneOffset.UTC))) {
            throw ApplicationException.of(AuthStatusCode.USER_NOT_FOUND);
        }
    }
}
