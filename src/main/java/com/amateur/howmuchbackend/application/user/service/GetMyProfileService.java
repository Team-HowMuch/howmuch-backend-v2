package com.amateur.howmuchbackend.application.user.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.user.GetMyProfileUseCase;
import com.amateur.howmuchbackend.application.port.in.user.response.MyProfileResponse;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
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
    private final LoadUserPort loadUserPort;

    @Override
    @Transactional(readOnly = true)
    public MyProfileResponse getMyProfile() {
        CurrentActor actor = currentActorPort.getCurrentActor();
        User user = loadUserPort.findById(actor.userId())
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));

        return MyProfileResponse.from(user);
    }
}
