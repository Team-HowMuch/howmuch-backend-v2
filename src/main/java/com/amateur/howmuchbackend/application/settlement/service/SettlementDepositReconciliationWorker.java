package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.ProcessSettlementDepositReconciliationJobsUseCase;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.settlement.*;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementTransferReconciliationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.settlement.*;
import com.amateur.howmuchbackend.domain.account.Transaction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementDepositReconciliationWorker implements ProcessSettlementDepositReconciliationJobsUseCase {
    private final SettlementDepositReconciliationJobStorePort jobs;
    private final LoadSettlementPort settlements;
    private final ExternalConnectionStorePort connections;
    private final LoadAccountPort accounts;
    private final SettlementTransferReconciliationPolicy policy;
    private final SettlementDepositReader reader;
    private final SettlementBankTransactionFetchService fetcher;
    private final SettlementDepositWriter writer;
    private final Clock clock;

    @Override
    public int processDue(int limit) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Reconciliation batch size must be 1..100");
        int processed = 0;
        for (int i = 0; i < limit; i++) {
            LocalDateTime now = now();
            var claim = jobs.claimDue(now, now.plus(SettlementTransferReconciliationPolicy.LEASE));
            if (claim.isEmpty()) break;
            process(claim.get());
            processed++;
        }
        return processed;
    }

    private void process(SettlementDepositReconciliationJob claim) {
        try {
            var settlement = settlements.findById(claim.settlementId()).orElse(null);
            if (settlement == null || !policy.canReconcile(settlement)) {
                cancel(claim);
                return;
            }
            var data = reader.load(settlement, now());
            var summary = reader.summarize(settlement, data.payableShares(), data.confirmedAmounts());
            List<Transaction> fetched = List.of();
            if (summary.confirmedAmount() < summary.expectedAmount()) {
                if (accounts.findActiveByIdAndUserId(data.snapshot().sourceAccountId(), settlement.creatorId()).isEmpty()) {
                    cancel(claim); return;
                }
                var connection = connections.findActiveByUserId(settlement.creatorId()).orElse(null);
                if (connection == null) { cancel(claim); return; }
                var snapshot = data.snapshot();
                fetched = fetcher.fetchAutomatically(connection, snapshot.sourceAccountId(), snapshot.organization(),
                        snapshot.accountNumber(), snapshot.createdAt(), now(), () -> renew(claim));
            }
            writer.reconcileAutomatically(claim, settlement.creatorId(), data.snapshot(),
                    settlement.status(), fetched, now());
        } catch (ApplicationException failure) {
            String code = failure.getStatusCode().getCode();
            if (code.equals(CommonStatusCode.TOO_MANY_REQUEST.getCode())) {
                jobs.finish(claim, SettlementDepositReconciliationStatus.EXHAUSTED, null, "QUOTA_EXHAUSTED", now());
            } else if (code.equals("SETTLEMENT_NOT_FOUND")
                    || code.equals("SETTLEMENT_DEPOSIT_RECONCILIATION_NOT_AVAILABLE")) {
                cancel(claim);
            } else {
                retry(claim);
            }
        } catch (RuntimeException failure) {
            retry(claim);
        }
    }

    private void renew(SettlementDepositReconciliationJob claim) {
        LocalDateTime now = now();
        if (!jobs.renew(claim, now, now.plus(SettlementTransferReconciliationPolicy.LEASE))) {
            throw ApplicationException.of(CommonStatusCode.CONCURRENT_UPDATE);
        }
    }
    private void cancel(SettlementDepositReconciliationJob claim) {
        jobs.finish(claim, SettlementDepositReconciliationStatus.CANCELLED, null, "TARGET_UNAVAILABLE", now());
    }
    private void retry(SettlementDepositReconciliationJob claim) {
        var decision = policy.retry(claim, now(), "PROVIDER_ERROR");
        jobs.finish(claim, decision.status(), decision.nextAttemptAt(), decision.failureCode(), now());
        log.info("Settlement deposit reconciliation attempt ended: settlementId={}, generation={}, attempt={}",
                claim.settlementId(), claim.generation(), claim.attemptCount());
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
}
