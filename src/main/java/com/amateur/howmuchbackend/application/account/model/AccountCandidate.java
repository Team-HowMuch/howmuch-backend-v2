package com.amateur.howmuchbackend.application.account.model;

import com.amateur.howmuchbackend.domain.account.AccountType;

import java.math.BigDecimal;

/**
 * Client-visible account candidate. It carries the hashed candidate key and masked number only, so
 * it is safe to keep in a short-lived server cache.
 */
public record AccountCandidate(
        String candidateKey,
        AccountType type,
        String name,
        String maskedNumber,
        BigDecimal balance,
        String currency,
        boolean alreadyLinked
) {
}
