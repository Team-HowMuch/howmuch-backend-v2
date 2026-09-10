package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

record SettlementListCursor(LocalDateTime requestedAt, UUID id) {
    static SettlementListCursor decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            if (value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException();
            }
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
                    .split("\\|", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            LocalDateTime requestedAt = LocalDateTime.parse(parts[0]);
            UUID id = UUID.fromString(parts[1]);
            if (requestedAt.getYear() < 1 || requestedAt.getYear() > 9999
                    || !id.toString().equals(parts[1])) {
                throw new IllegalArgumentException();
            }
            return new SettlementListCursor(requestedAt, id);
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_CURSOR);
        }
    }

    String encode() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (requestedAt + "|" + id).getBytes(StandardCharsets.UTF_8)
        );
    }
}
