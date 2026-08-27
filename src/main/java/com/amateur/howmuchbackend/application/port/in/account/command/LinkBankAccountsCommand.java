package com.amateur.howmuchbackend.application.port.in.account.command;

import java.util.List;

public record LinkBankAccountsCommand(
        Long connectionId,
        String organization,
        List<String> candidateKeys,
        String birthDate
) {
}
