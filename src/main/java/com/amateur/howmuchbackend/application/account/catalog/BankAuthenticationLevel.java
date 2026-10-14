package com.amateur.howmuchbackend.application.account.catalog;

public enum BankAuthenticationLevel {
    CERTIFICATE_ONLY(1),
    CERTIFICATE_AND_ID_PASSWORD(2),
    CERTIFICATE_ID_PASSWORD_AND_ACCOUNT_CREDENTIALS(3);

    private final int value;

    BankAuthenticationLevel(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public boolean supportsIdPassword() {
        return value >= CERTIFICATE_AND_ID_PASSWORD.value;
    }

    public boolean requiresAccountCredentials() {
        return this == CERTIFICATE_ID_PASSWORD_AND_ACCOUNT_CREDENTIALS;
    }

    public static BankAuthenticationLevel fromAttribute(String attribute) {
        if (attribute == null) {
            return CERTIFICATE_ONLY;
        }
        return switch (attribute.trim()) {
            case "1" -> CERTIFICATE_ONLY;
            case "2" -> CERTIFICATE_AND_ID_PASSWORD;
            case "3" -> CERTIFICATE_ID_PASSWORD_AND_ACCOUNT_CREDENTIALS;
            default -> CERTIFICATE_ONLY;
        };
    }
}
