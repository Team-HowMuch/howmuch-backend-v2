package com.amateur.howmuchbackend.adapter.out.security;

import com.amateur.howmuchbackend.application.port.out.auth.CurrentAuthSessionPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.infra.security.jwt.JwtPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SpringSecuritySessionAdapter implements CurrentAuthSessionPort {
    @Override
    public AuthenticatedSession getCurrentSession() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof JwtPrincipal principal)) {
            throw ApplicationException.of(CommonStatusCode.UNAUTHORIZED);
        }
        return new AuthenticatedSession(principal.userId(), principal.sessionId());
    }
}
