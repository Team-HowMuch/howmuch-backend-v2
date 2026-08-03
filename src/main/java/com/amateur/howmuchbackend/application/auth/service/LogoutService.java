package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.in.auth.LogoutUseCase;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LogoutService implements LogoutUseCase {
    private final AuthSessionPort authSessionPort;
    private final CurrentActorPort currentActorPort;

    @Override
    public void logout() {
        authSessionPort.deleteByUserId(currentActorPort.getCurrentActor().userId());
    }
}
