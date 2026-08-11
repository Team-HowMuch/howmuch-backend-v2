package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.user.GetMyProfileImageUseCase;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.user.UserRepositoryPort;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GetMyProfileImageService implements GetMyProfileImageUseCase {
    private final CurrentActorPort currentActorPort;
    private final UserRepositoryPort userRepositoryPort;
    private final ProfileImageStoragePort profileImageStoragePort;

    @Override
    @Transactional(readOnly = true)
    public ProfileImage getMyProfileImage() {
        CurrentActor actor = currentActorPort.getCurrentActor();
        User user = userRepositoryPort.findById(actor.userId())
                .orElseThrow(() -> ApplicationException.of(CommonStatusCode.ENDPOINT_NOT_FOUND));
        if (user.avatarFileName() == null) {
            throw ApplicationException.of(CommonStatusCode.ENDPOINT_NOT_FOUND);
        }

        return profileImageStoragePort.load(user.avatarFileName())
                .orElseThrow(() -> ApplicationException.of(CommonStatusCode.ENDPOINT_NOT_FOUND));
    }
}
