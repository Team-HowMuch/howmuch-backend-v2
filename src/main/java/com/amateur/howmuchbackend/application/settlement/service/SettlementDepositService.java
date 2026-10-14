package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementDepositSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ReconcileSettlementDepositsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementDepositSummaryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.account.Transaction;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementDepositService implements
        ReconcileSettlementDepositsUseCase,
        GetSettlementDepositSummaryUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementReceivingAccountSnapshotPort loadSnapshotPort;
    private final ExternalConnectionStorePort connectionStorePort;
    private final SettlementBankTransactionFetchService transactionFetchService;
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
        List<Transaction> fetchedTransactions =
                transactionFetchService.fetch(
                        connection,
                        snapshot.sourceAccountId(),
                        snapshot.organization(),
                        snapshot.accountNumber(),
                        snapshot.createdAt(),
                        now
                );
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

    /**
     * Reuses the regular CODEF fetch and matching flow while keeping the external call outside
     * database transactions. The writer revalidates status, account, and snapshot under locks.
     */
    public void refreshForSuspension(
            UUID settlementId,
            Long userId,
            SettlementReceivingAccountSnapshot expectedSnapshot,
            SettlementStatus expectedStatus,
            LocalDateTime now
    ) {
        Settlement settlement = loadOwnedSettlement(settlementId, userId);
        if (settlement.status() != expectedStatus) {
            throw ApplicationException.of(
                    CommonStatusCode.CONCURRENT_UPDATE
            );
        }
        ensureSuspensionRefreshAvailable(expectedStatus);
        SettlementReceivingAccountSnapshot snapshot = loadSnapshot(settlementId);
        if (!snapshot.equals(expectedSnapshot)) {
            throw ApplicationException.of(
                    CommonStatusCode.CONCURRENT_UPDATE
            );
        }
        ExternalConnection connection = connectionStorePort.findActiveByUserId(userId)
                .orElseThrow(() -> ApplicationException.of(
                        ExternalConnectionStatusCode.EXTERNAL_CONNECTION_NOT_FOUND
                ));
        List<Transaction> fetchedTransactions =
                transactionFetchService.fetch(
                        connection,
                        snapshot.sourceAccountId(),
                        snapshot.organization(),
                        snapshot.accountNumber(),
                        snapshot.createdAt(),
                        now
                );
        writer.reconcileForSuspension(
                settlementId,
                userId,
                snapshot,
                expectedStatus,
                fetchedTransactions,
                now
        );
    }

    private SettlementReceivingAccountSnapshot loadSnapshot(UUID settlementId) {
        return loadSnapshotPort.findBySettlementId(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND
                ));
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

    private void ensureSuspensionRefreshAvailable(SettlementStatus status) {
        if (status != SettlementStatus.REQUESTED
                && status != SettlementStatus.AWAITING_DEPOSIT) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_NOT_SUSPENDABLE
            );
        }
    }
}
