package com.amateur.howmuchbackend.application.port.in.account.result;

public record BankCatalogResult(String organization, String name, String iconUrl, int authenticationLevel) {
    public BankCatalogResult(String organization, String name, String iconUrl, boolean requiresAccountCredentials) {
        this(organization, name, iconUrl, requiresAccountCredentials ? 3 : 1);
    }

    public boolean requiresAccountCredentials() {
        return authenticationLevel == 3;
    }
}
