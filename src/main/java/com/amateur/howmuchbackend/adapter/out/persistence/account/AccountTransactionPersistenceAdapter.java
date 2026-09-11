package com.amateur.howmuchbackend.adapter.out.persistence.account;

import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountMonthlySummaryJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountTransactionJpaEntity;
import com.amateur.howmuchbackend.adapter.out.persistence.account.repository.AccountMonthlySummaryJpaRepository;
import com.amateur.howmuchbackend.adapter.out.persistence.account.repository.AccountTransactionJpaRepository;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountTransactionPort;
import com.amateur.howmuchbackend.application.port.out.account.SaveAccountTransactionPort;
import com.amateur.howmuchbackend.domain.account.AccountMonthlySummary;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
    public List<Transaction> findByAccountIdAndYearMonth(Long accountId, YearMonth yearMonth) {
        LocalDateTime start = monthStartUtc(yearMonth);
        return transactionRepository
                .findByAccountIdAndTransactedAtGreaterThanEqualAndTransactedAtLessThanOrderByTransactedAtDescIdDesc(
                        accountId, start, monthStartUtc(yearMonth.plusMonths(1))
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LinkedSettlement> findLinkedSettlements(
            Long accountId,
            List<String> externalTransactionKeys
    ) {
        if (externalTransactionKeys.isEmpty()) {
            return List.of();
        }
        return transactionRepository.findLinkedSettlements(
                        accountId,
                        externalTransactionKeys
                )
                .stream()
                .map(projection -> new LinkedSettlement(
                        projection.getExternalTransactionKey(),
                        projection.getSettlementId(),
                        projection.getTitle(),
                        SettlementStatus.valueOf(projection.getStatus())
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Transaction> findByAccountIdAndId(Long accountId, Long transactionId) {
        return transactionRepository.findByAccountIdAndId(accountId, transactionId)
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Transaction> findIncomeByAccountIdBetween(
            Long accountId,
            LocalDateTime start,
            LocalDateTime end
    ) {
        return transactionRepository
                .findByAccountIdAndTypeAndTransactedAtGreaterThanEqualAndTransactedAtLessThanEqualOrderByTransactedAtAscIdAsc(
                        accountId,
                        AccountTransactionType.INCOME,
                        start,
                        end
                ).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountMonthlySummary> findSummary(Long accountId, YearMonth yearMonth) {
        return summaryRepository.findByAccountIdAndYearMonth(accountId, yearMonth.atDay(1))
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
    public void saveAllIgnoringDuplicates(List<Transaction> transactions) {
        Set<AccountMonth> changedMonths = new HashSet<>();
        for (Transaction transaction : transactions) {
            int inserted = transactionRepository.insertIgnoringDuplicate(
                    transaction.accountId(), transaction.externalTransactionKey(), transaction.type().name(),
                    transaction.amount(), transaction.transactedAt(), transaction.description(),
                    transaction.afterBalance()
            );
            if (inserted > 0) {
                changedMonths.add(new AccountMonth(
                        transaction.accountId(),
                        YearMonth.from(transaction.transactedAt()
                                .atZone(ZoneOffset.UTC)
                                .withZoneSameInstant(SEOUL))
                ));
            }
        }
        changedMonths.forEach(accountMonth -> summaryRepository.deleteByAccountIdAndYearMonth(
                accountMonth.accountId(),
                accountMonth.yearMonth().atDay(1)
        ));
    }

    @Override
    @Transactional
    public void saveSummaryIfAbsent(AccountMonthlySummary summary) {
        summaryRepository.insertIfAbsent(
                summary.accountId(), summary.yearMonth().atDay(1), summary.monthlyExpenseAmount(),
                summary.expenseCount(), summary.lastUsedAt()
        );
    }

    private Transaction toDomain(AccountTransactionJpaEntity entity) {
        return new Transaction(
                entity.getId(), entity.getAccountId(), entity.getExternalTransactionKey(), entity.getType(),
                entity.getAmount(), entity.getTransactedAt(), entity.getDescription(), entity.getAfterBalance()
        );
    }

    private AccountMonthlySummary toDomain(AccountMonthlySummaryJpaEntity entity) {
        LocalDate month = entity.getYearMonth();
        return new AccountMonthlySummary(
                entity.getId(), entity.getAccountId(), YearMonth.from(month),
                entity.getMonthlyExpenseAmount(), entity.getExpenseCount(), entity.getLastUsedAt()
        );
    }

    private LocalDateTime monthStartUtc(YearMonth yearMonth) {
        return yearMonth.atDay(1).atStartOfDay(SEOUL)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    private record AccountMonth(Long accountId, YearMonth yearMonth) {
    }
}
