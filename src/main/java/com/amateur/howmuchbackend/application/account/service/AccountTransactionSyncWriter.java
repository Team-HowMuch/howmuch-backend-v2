package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.settlement.netting.QueueNettedDepositDetectionPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.AccountMonthlySummary;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * Persists a fetched account transaction batch in one short transaction.
 *
 * <p>The caller performs the external CODEF call outside any transaction. This writer re-checks
 * that the account is still active under a row lock so a result fetched before account deletion or
 * connection changes cannot be written afterwards.
 */
@Component
@RequiredArgsConstructor
public class AccountTransactionSyncWriter {
    private final LoadAccountPort loadAccountPort;
    private final LoadAccountTransactionPort loadTransactionPort;
    private final SaveAccountTransactionPort saveTransactionPort;
    private final QueueNettedDepositDetectionPort nettingDetection;

    @Transactional
    public AccountMonthlySummary persist(
            Long userId,
            Long accountId,
            YearMonth yearMonth,
            List<Transaction> transactions,
            LocalDateTime observedAt
    ) {
        loadAccountPort.findActiveByIdAndUserIdForUpdate(accountId, userId)
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));

        saveTransactionPort.saveAllIgnoringDuplicates(transactions);
        if (!transactions.isEmpty()) {
            nettingDetection.observed(accountId, observedAt);
        }
        AccountMonthlySummary calculated = loadTransactionPort.calculateSummary(accountId, yearMonth);
        saveTransactionPort.saveSummaryIfAbsent(calculated);
        return loadTransactionPort.findSummary(accountId, yearMonth).orElse(calculated);
    }
}
