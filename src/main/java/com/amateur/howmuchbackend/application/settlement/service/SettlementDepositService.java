package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.account.service.AccountCredentialProtectionService;
import com.amateur.howmuchbackend.application.account.AccountTransactionKey;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementDepositSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ReconcileSettlementDepositsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDepositSummaryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.BankTransactionPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementDepositService implements
        ReconcileSettlementDepositsUseCase,
        GetSettlementDepositSummaryUseCase {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementReceivingAccountSnapshotPort loadSnapshotPort;
    private final ExternalConnectionStorePort connectionStorePort;
    private final BankTransactionPort bankTransactionPort;
    private final BankCatalog bankCatalog;
    private final AccountCredentialProtectionService credentialProtection;
    private final SettlementDepositWriter writer;
    private final SettlementDepositReader reader;

    @Override
    public SettlementDepositSummaryResult reconcile(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlement(settlementId, actor.userId());
        ensureAvailable(settlement.status());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (settlement.status() == SettlementStatus.COMPLETED) {
            return reader.read(settlementId, actor.userId(), now);
        }

        SettlementReceivingAccountSnapshot snapshot = loadSnapshot(settlement.id());
        ExternalConnection connection = connectionStorePort.findActiveByUserId(actor.userId())
                .orElseThrow(() -> ApplicationException.of(
                        ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND
                ));
        List<Transaction> fetchedTransactions = fetchTransactions(connection, snapshot, now);
        return writer.reconcile(
                settlementId,
                actor.userId(),
                snapshot,
                fetchedTransactions,
                now
        );
    }

    @Override
    public SettlementDepositSummaryResult get(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        return reader.read(
                settlementId,
                actor.userId(),
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    private SettlementReceivingAccountSnapshot loadSnapshot(UUID settlementId) {
        return loadSnapshotPort.findBySettlementId(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND
                ));
    }

    private List<Transaction> fetchTransactions(
            ExternalConnection connection,
            SettlementReceivingAccountSnapshot snapshot,
            LocalDateTime now
    ) {
        LocalDate startDate = seoulDate(snapshot.createdAt());
        LocalDate endDate = seoulDate(now);
        if (startDate.isAfter(endDate)) {
            return List.of();
        }
        BankAccountCredentials credentials = bankCatalog.requiresAccountCredentials(snapshot.organization())
                ? credentialProtection.reveal(connection.userId(), connection.id(), snapshot.organization()) : null;

        List<Transaction> result = new ArrayList<>();
        YearMonth month = YearMonth.from(startDate);
        YearMonth lastMonth = YearMonth.from(endDate);
        while (!month.isAfter(lastMonth)) {
            LocalDate periodStart = month.equals(YearMonth.from(startDate))
                    ? startDate : month.atDay(1);
            LocalDate periodEnd = month.equals(lastMonth)
                    ? endDate : month.atEndOfMonth();
            bankTransactionPort.fetch(
                    connection.connectedId(),
                    snapshot.organization(),
                    snapshot.accountNumber(),
                    periodStart,
                    periodEnd,
                    credentials
            ).stream()
                    .map(transaction -> new Transaction(
                            null,
                            snapshot.sourceAccountId(),
                            AccountTransactionKey.from(snapshot.accountNumber(), transaction),
                            transaction.type(),
                            transaction.amount(),
                            transaction.transactedAt(),
                            transaction.description(),
                            transaction.afterBalance(),
                            transaction.description1(),
                            transaction.description2(),
                            transaction.description3(),
                            transaction.description4()
                    ))
                    .forEach(result::add);
            month = month.plusMonths(1);
        }
        return List.copyOf(result);
    }

    private Settlement loadOwnedSettlement(UUID settlementId, Long userId) {
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                ));
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        return settlement;
    }

    private void ensureAvailable(SettlementStatus status) {
        if (status != SettlementStatus.AWAITING_DEPOSIT
                && status != SettlementStatus.COMPLETED) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_DEPOSIT_RECONCILIATION_NOT_AVAILABLE
            );
        }
    }

    private LocalDate seoulDate(LocalDateTime utcDateTime) {
        return utcDateTime.atZone(ZoneOffset.UTC)
                .withZoneSameInstant(SEOUL)
                .toLocalDate();
    }
}
