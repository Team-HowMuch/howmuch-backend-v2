package com.amateur.howmuchbackend.adapter.out.security.jwt;

import com.amateur.howmuchbackend.application.port.out.auth.AuthTokenPort;
import com.amateur.howmuchbackend.application.port.out.auth.WidgetTokenPort;
import com.amateur.howmuchbackend.application.port.out.auth.jwt.JwtPayload;
import com.amateur.howmuchbackend.application.port.out.auth.jwt.enums.JwtTokenType;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.infra.security.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtTokenProvider implements AuthTokenPort, WidgetTokenPort {

    private static final String TOKEN_TYPE_CLAIM = "tokenType";
    private static final String SESSION_ID_CLAIM = "sessionId";

    private final JwtProperties jwtProperties;
    private final SecretKey secretKey;

    public JwtTokenProvider(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
        this.secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String createAccessToken(User user, String sessionId) {
        return createToken(user, sessionId, JwtTokenType.ACCESS, jwtProperties.accessExpiration());
    }

    @Override
    public String createRefreshToken(User user, String sessionId) {
        return createToken(user, sessionId, JwtTokenType.REFRESH, jwtProperties.refreshExpiration());
    }

    @Override
    public String createWidgetToken(Long userId, String sessionId, LocalDateTime expiresAt) {
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(userId))
                .claim(TOKEN_TYPE_CLAIM, JwtTokenType.WIDGET.name())
                .claim(SESSION_ID_CLAIM, sessionId)
                .issuedAt(toDate(LocalDateTime.now(ZoneOffset.UTC)))
                .expiration(toDate(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    @Override
    public Optional<JwtPayload> parsePayload(String token) {
        try {
            return Optional.of(toJwtPayload(getClaims(token)));
        } catch (JwtException | IllegalArgumentException | NullPointerException exception) {
            return Optional.empty();
        }
    }

    private String createToken(User user, String sessionId, JwtTokenType tokenType, long expirationSeconds) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAt = now.plusSeconds(expirationSeconds);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(user.id()))
                .claim(TOKEN_TYPE_CLAIM, tokenType.name())
                .claim(SESSION_ID_CLAIM, sessionId)
                .issuedAt(toDate(now))
                .expiration(toDate(expiresAt))
                .signWith(secretKey)
                .compact();
    }

    private JwtPayload toJwtPayload(Claims claims) {
        Long userId = Long.valueOf(claims.getSubject());
        String sessionId = claims.get(SESSION_ID_CLAIM, String.class);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("JWT sessionId claim is blank.");
        }

        return new JwtPayload(
                userId,
                sessionId,
                JwtTokenType.valueOf(claims.get(TOKEN_TYPE_CLAIM, String.class)),
                fromDate(claims.getExpiration())
        );
    }

    private Date toDate(LocalDateTime value) {
        return Date.from(value.toInstant(ZoneOffset.UTC));
    }

    private LocalDateTime fromDate(Date value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZoneOffset.UTC);
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
