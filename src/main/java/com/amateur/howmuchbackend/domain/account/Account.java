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
    private static final String DEMAND_DEPOSIT = "11";
    private static final String WON = "KRW";

    public boolean isActiveWonDemandDeposit() {
        return active
                && type == AccountType.DEPOSIT_TRUST
                && DEMAND_DEPOSIT.equals(productCode)
                && currency != null
                && WON.equalsIgnoreCase(currency);
    }
}
