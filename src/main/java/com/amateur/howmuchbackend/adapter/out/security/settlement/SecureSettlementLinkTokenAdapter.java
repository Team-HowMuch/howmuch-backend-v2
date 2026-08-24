package com.amateur.howmuchbackend.adapter.out.security.settlement;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementLinkTokenPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import com.amateur.howmuchbackend.infra.settlement.config.SettlementLinkProperties;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

@Component
public class SecureSettlementLinkTokenAdapter implements SettlementLinkTokenPort {
    private static final int TOKEN_BYTES = 32;
    private static final Pattern RAW_TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SettlementLinkProperties properties;

    public SecureSettlementLinkTokenAdapter(SettlementLinkProperties properties) {
        this.properties = properties;
    }

    @Override
    public IssuedSettlementLinkToken issue(LocalDateTime now) {
        byte[] random = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(random);
        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        return new IssuedSettlementLinkToken(
                hash(rawToken),
                properties.baseUrl() + "/" + rawToken,
                now.plus(properties.ttl())
        );
    }

    @Override
    public String hash(String rawToken) {
        if (rawToken == null || !RAW_TOKEN_PATTERN.matcher(rawToken).matches()) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_LINK_NOT_FOUND);
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available.", impossible);
        }
    }
}
