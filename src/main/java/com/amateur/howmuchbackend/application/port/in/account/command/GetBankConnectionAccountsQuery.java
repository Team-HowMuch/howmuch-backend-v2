package com.amateur.howmuchbackend.application.port.in.account.command;

public record GetBankConnectionAccountsQuery(Long connectionId, String organization, String birthDate) {
    @Override
    public String toString() {
        return "GetBankConnectionAccountsQuery[connectionId=%s, organization=%s, birthDate=***]"
                .formatted(connectionId, organization);
    }
}
