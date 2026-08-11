package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.user.GetMyProfileUseCase;
import com.amateur.howmuchbackend.application.port.in.user.response.MyProfileResponse;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.UserRepositoryPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetMyProfileService implements GetMyProfileUseCase {
    private final CurrentActorPort currentActorPort;
    private final UserRepositoryPort userRepositoryPort;

    @Override
    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile() {
        CurrentActor actor = currentActorPort.getCurrentActor();
        User user = userRepositoryPort.findById(actor.userId())
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));

        return new MyProfileResponse(
                user.id(),
                user.name(),
                user.nickname(),
                user.birthDate(),
                user.gender(),
                user.phoneNumber(),
                user.avatarFileName() != null,
                user.phoneVerified(),
                user.role(),
                user.lastLoginAt(),
                user.createdAt()
        );
    }
}
