package com.amateur.howmuchbackend.domain.account;

import java.math.BigDecimal;

public record Account(
        Long id,
        Long userId,
        Long externalConnectionId,
        String organization,
        String candidateKey,
        String externalAccountKey,
        AccountType type,
        String name,
        String maskedNumber,
        BigDecimal balance,
        String currency,
        boolean active,
        String productCode,
        boolean isDefault,
        int displayOrder
) {
}
