package com.amateur.howmuchbackend.application.port.in.account.command;

import java.time.LocalDate;
import java.util.UUID;

public record RequestAccountTransactionSyncCommand(
        Long accountId,
        UUID idempotencyKey,
        LocalDate fromDate,
        LocalDate toDate
) {
}
