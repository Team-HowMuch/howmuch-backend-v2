package com.amateur.howmuchbackend.application.port.out.account;

import com.amateur.howmuchbackend.domain.account.AccountMonthlySummary;
import com.amateur.howmuchbackend.domain.account.Transaction;

import java.util.List;

public interface SaveAccountTransactionPort {
    void saveAllIgnoringDuplicates(List<Transaction> transactions);

    void saveSummaryIfAbsent(AccountMonthlySummary summary);
}
