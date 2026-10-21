package com.amateur.howmuchbackend.infra.security.jwt;

import com.amateur.howmuchbackend.application.port.out.auth.AuthSessionPort;
import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.auth.jwt.JwtPayload;
import com.amateur.howmuchbackend.application.port.out.auth.jwt.enums.JwtTokenType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final java.util.regex.Pattern WIDGET_RECONCILE_PATH = java.util.regex.Pattern.compile(
            "/api/v2/settlements/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/deposits/reconcile");

    private final AuthTokenPort authTokenPort;
    private final AuthSessionPort authSessionPort;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authTokenPort.parsePayload(token)
                    .filter(payload -> payload.isType(JwtTokenType.ACCESS)
                            || (payload.isType(JwtTokenType.WIDGET) && isWidgetRequest(request)))
                    .ifPresent(this::authenticateCurrentSession);
        }

        filterChain.doFilter(request, response);
    }

    private boolean isWidgetRequest(HttpServletRequest request) {
        String path = request.getServletPath();
        return ("GET".equals(request.getMethod())
                && "/api/v2/settlements/widget-summary".equals(path))
                || ("POST".equals(request.getMethod())
                && ("/api/v2/settlements/widget-summary/refresh".equals(path)
                || WIDGET_RECONCILE_PATH.matcher(path).matches()));
    }

    private void authenticateCurrentSession(JwtPayload payload) {
        Long userId = payload.userId();
        String sessionId = payload.sessionId();
        if (!authSessionPort.existsActiveByUserIdAndSessionId(userId, sessionId, LocalDateTime.now(ZoneOffset.UTC))) {
            return;
        }

        JwtPrincipal principal = new JwtPrincipal(userId, sessionId);
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(principal, null, AuthorityUtils.NO_AUTHORITIES);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String resolveToken(HttpServletRequest request) {
        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (!StringUtils.hasText(authorizationHeader) || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }

        return authorizationHeader.substring(BEARER_PREFIX.length());
    }
}
