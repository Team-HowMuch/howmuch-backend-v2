package com.amateur.howmuchbackend.application.port.in.account.result;

public record BankCatalogResult(String organization, String name, String iconUrl, boolean requiresAccountCredentials) {
}
