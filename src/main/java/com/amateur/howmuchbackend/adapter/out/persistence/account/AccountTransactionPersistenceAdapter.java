package com.amateur.howmuchbackend.adapter.out.persistence.account;

import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountMonthlySummaryJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountTransactionJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.account.repository.AccountMonthlySummaryJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.account.repository.AccountTransactionJpaRepository;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountTransactionPort;
import com.amateur.howmuchbackend.domain.account.AccountMonthlySummary;
import com.amateur.howmuchbackend.domain.account.AccountTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class AccountTransactionPersistenceAdapter
        implements LoadAccountTransactionPort, SaveAccountTransactionPort {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final AccountTransactionJpaRepository transactionRepository;
    private final AccountMonthlySummaryJpaRepository summaryRepository;

    @Override
    @Transactional(readOnly = true)
    public Map<Long, LocalDateTime> findLastUsedAtByAccountIds(List<Long> accountIds) {
        if (accountIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, LocalDateTime> result = new LinkedHashMap<>();
        for (Object[] row : transactionRepository.findLastUsedAtByAccountIds(accountIds)) {
            result.put((Long) row[0], (LocalDateTime) row[1]);
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountTransaction> findByAccountIdAndYearMonth(Long accountId, YearMonth yearMonth) {
        LocalDateTime start = monthStartUtc(yearMonth);
        return transactionRepository
                .findByAccount_IdAndTransactedAtGreaterThanEqualAndTransactedAtLessThanOrderByTransactedAtDescIdDesc(
                        accountId, start, monthStartUtc(yearMonth.plusMonths(1))
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountMonthlySummary> findSummary(Long accountId, YearMonth yearMonth) {
        return summaryRepository.findByAccount_IdAndYearMonth(accountId, yearMonth.atDay(1))
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public AccountMonthlySummary calculateSummary(Long accountId, YearMonth yearMonth) {
        LocalDateTime start = monthStartUtc(yearMonth);
        var projection = transactionRepository.calculateSummary(
                accountId, start, monthStartUtc(yearMonth.plusMonths(1))
        );
        return new AccountMonthlySummary(
                null, accountId, yearMonth, projection.getMonthlyExpenseAmount(),
                projection.getExpenseCount(), projection.getLastUsedAt()
        );
    }

    @Override
    @Transactional
    public void saveAllIgnoringDuplicates(List<AccountTransaction> transactions) {
        for (AccountTransaction transaction : transactions) {
            transactionRepository.insertIgnoringDuplicate(
                    transaction.accountId(), transaction.externalTransactionKey(), transaction.type().name(),
                    transaction.amount(), transaction.transactedAt(), transaction.description(),
                    transaction.afterBalance()
            );
        }
    }

    @Override
    @Transactional
    public void saveSummaryIfAbsent(AccountMonthlySummary summary) {
        summaryRepository.insertIfAbsent(
                summary.accountId(), summary.yearMonth().atDay(1), summary.monthlyExpenseAmount(),
                summary.expenseCount(), summary.lastUsedAt()
        );
    }

    private AccountTransaction toDomain(AccountTransactionJpaEntity entity) {
        return new AccountTransaction(
                entity.getId(), entity.getAccount().getId(), entity.getExternalTransactionKey(), entity.getType(),
                entity.getAmount(), entity.getTransactedAt(), entity.getDescription(), entity.getAfterBalance()
        );
    }

    private AccountMonthlySummary toDomain(AccountMonthlySummaryJpaEntity entity) {
        LocalDate month = entity.getYearMonth();
        return new AccountMonthlySummary(
                entity.getId(), entity.getAccount().getId(), YearMonth.from(month),
                entity.getMonthlyExpenseAmount(), entity.getExpenseCount(), entity.getLastUsedAt()
        );
    }

    private LocalDateTime monthStartUtc(YearMonth yearMonth) {
        return yearMonth.atDay(1).atStartOfDay(SEOUL)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }
}
