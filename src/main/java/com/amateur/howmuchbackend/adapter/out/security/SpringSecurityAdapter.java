package com.amateur.howmuchbackend.adapter.out.security;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.infra.security.jwt.JwtPrincipal;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SpringSecurityAdapter implements CurrentActorPort {

    @Override
    public CurrentActor getCurrentActor() {
        Authentication authentication =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication();

        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication
                instanceof AnonymousAuthenticationToken) {
            throw new ApplicationException(CommonStatusCode.UNAUTHORIZED);
        }

        Object principal = authentication.getPrincipal();

        if (!(principal instanceof JwtPrincipal jwtPrincipal)) {
            throw new IllegalStateException(
                        "지원하지 않는 인증 Principal 타입입니다: "
                            + principal.getClass().getName()
            );
        }

        return new CurrentActor(jwtPrincipal.userId());
    }
}
