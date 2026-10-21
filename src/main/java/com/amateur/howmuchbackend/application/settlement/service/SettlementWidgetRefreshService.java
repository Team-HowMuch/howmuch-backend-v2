package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.RefreshSettlementWidgetSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementWidgetSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementWidgetSummaryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementWidgetPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementReceivingAccountSnapshotPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementWidgetRefreshStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementReceivingAccountSnapshot;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementWidgetRefreshService implements RefreshSettlementWidgetSummaryUseCase {
    private final CurrentActorPort actorPort;
    private final LoadSettlementWidgetPort widgetPort;
    private final LoadSettlementPort settlementPort;
    private final LoadSettlementReceivingAccountSnapshotPort snapshotPort;
    private final ExternalConnectionStorePort connectionPort;
    private final LoadAccountPort accountPort;
    private final SettlementWidgetRefreshStorePort refreshStore;
    private final SettlementBankTransactionFetchService fetchService;
    private final SettlementDepositWriter writer;
    private final GetSettlementWidgetSummaryUseCase summaryUseCase;

    @Override
    public SettlementWidgetSummaryResult refresh() {
        Long userId = actorPort.getCurrentActor().userId();
        String owner = UUID.randomUUID().toString();
        if (!refreshStore.tryAcquire(userId, owner)) {
            throw ApplicationException.of(CommonStatusCode.TOO_MANY_REQUEST);
        }
        LocalDateTime successfulAt = null;
        try {
            LocalDateTime until = LocalDateTime.now(ZoneOffset.UTC);
            var groups = new LinkedHashMap<AccountKey, List<Target>>();
            var ids = widgetPort.findAllInProgress(userId).stream().filter(row -> row.creator())
                    .map(row -> row.settlementId()).distinct().toList();
            for (UUID id : ids) {
                Settlement settlement = settlementPort.findById(id).orElse(null);
                // 조회 중 종료되거나 삭제된 정산은 대조 대상에서 제외한다.
                if (settlement == null || !settlement.isCreatedBy(userId)
                        || (settlement.status() != SettlementStatus.REQUESTED
                        && settlement.status() != SettlementStatus.AWAITING_DEPOSIT)) continue;
                var snapshot = snapshotPort.findBySettlementId(id).orElse(null);
                if (snapshot == null) {
                    log.info("Skipping widget reconciliation: settlementId={}, code={}", id,
                            SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND.getCode());
                    continue;
                }
                var key = new AccountKey(snapshot.sourceAccountId(), snapshot.organization(), snapshot.accountNumber());
                groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new Target(settlement, snapshot));
            }
            if (!groups.isEmpty() && reconcileGroups(userId, owner, groups, until)) {
                successfulAt = until;
            }
        } finally {
            refreshStore.finish(userId, owner, successfulAt);
        }
        return summaryUseCase.getSummary();
    }

    private boolean reconcileGroups(Long userId, String owner, Map<AccountKey, List<Target>> groups,
            LocalDateTime until) {
        var connection = connectionPort.findActiveByUserId(userId).orElse(null);
        if (connection == null) return false;
        boolean reconciled = false;
        for (var entry : groups.entrySet()) {
            requireLease(userId, owner);
            var key = entry.getKey();
            if (accountPort.findActiveByIdAndUserId(key.accountId(), userId).isEmpty()) {
                log.info("Skipping widget reconciliation: accountId={}, reason=ACCOUNT_UNAVAILABLE", key.accountId());
                continue;
            }
            var targets = entry.getValue();
            var from = targets.stream().map(target -> target.snapshot().createdAt())
                    .min(LocalDateTime::compareTo).orElseThrow();
            var transactions = fetchService.fetchForWidget(connection, key.accountId(), key.organization(),
                    key.accountNumber(), from, until, () -> requireLease(userId, owner));
            for (var target : targets) {
                requireLease(userId, owner);
                try {
                    // REQUESTED도 이미 확정된 Share만 기존 대조 규칙으로 매칭한다.
                    writer.reconcileForWidget(target.settlement().id(), userId, target.snapshot(),
                            target.settlement().status(), transactions, until);
                    reconciled = true;
                } catch (ApplicationException failure) {
                    if (!isUnavailableTarget(failure)) throw failure;
                    log.info("Skipping widget reconciliation: settlementId={}, code={}",
                            target.settlement().id(), failure.getStatusCode().getCode());
                }
            }
        }
        requireLease(userId, owner);
        return reconciled;
    }

    private boolean isUnavailableTarget(ApplicationException failure) {
        var status = failure.getStatusCode();
        return status == CommonStatusCode.CONCURRENT_UPDATE
                || status == SettlementStatusCode.SETTLEMENT_NOT_FOUND
                || status == SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND
                || status == SettlementStatusCode.SETTLEMENT_DEPOSIT_RECONCILIATION_NOT_AVAILABLE;
    }

    private void requireLease(Long userId, String owner) {
        if (!refreshStore.renew(userId, owner)) {
            throw ApplicationException.of(CommonStatusCode.TEMPORARILY_UNAVAILABLE);
        }
    }

    private record AccountKey(Long accountId, String organization, String accountNumber) {
        @Override public String toString() { return "AccountKey[accountId=" + accountId + ", accountNumber=***]"; }
    }
    private record Target(Settlement settlement, SettlementReceivingAccountSnapshot snapshot) {}
}
