package com.amateur.howmuchbackend.application.port.in.account.command;

public record ConnectBankCommand(
        String organization,
        String loginId,
        String password,
        String birthDate,
        String accountNumber,
        String accountPassword
) {
    @Override
    public String toString() {
        return ("ConnectBankCommand[organization=%s, loginId=***, password=***, birthDate=***, "
                + "accountNumber=***, accountPassword=***]").formatted(organization);
    }
}
