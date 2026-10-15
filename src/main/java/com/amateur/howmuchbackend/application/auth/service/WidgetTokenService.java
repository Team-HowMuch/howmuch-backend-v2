package com.amateur.howmuchbackend.application.auth.service;

import com.amateur.howmuchbackend.application.port.in.auth.IssueWidgetTokenUseCase;
import com.amateur.howmuchbackend.application.port.in.auth.result.WidgetTokenResult;
import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentAuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.WidgetTokenPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class WidgetTokenService implements IssueWidgetTokenUseCase {
    private final CurrentAuthSessionPort currentSessionPort;
    private final AuthSessionPort sessionPort;
    private final WidgetTokenPort tokenPort;
    private final LoadUserPort userPort;

    @Override
    public WidgetTokenResult issue() {
        var actor = currentSessionPort.getCurrentSession();
        var session = sessionPort.findByUserId(actor.userId())
                .filter(value -> value.sessionId().equals(actor.sessionId()))
                .filter(value -> !value.isExpired(LocalDateTime.now(ZoneOffset.UTC)))
                .orElseThrow(() -> ApplicationException.of(CommonStatusCode.UNAUTHORIZED));
        userPort.findById(actor.userId()).filter(User::active)
                .orElseThrow(() -> ApplicationException.of(CommonStatusCode.UNAUTHORIZED));
        var expiresAt = session.expiresAt().truncatedTo(ChronoUnit.SECONDS);
        return new WidgetTokenResult(
                tokenPort.createWidgetToken(actor.userId(), actor.sessionId(), expiresAt), expiresAt
        );
    }
}
