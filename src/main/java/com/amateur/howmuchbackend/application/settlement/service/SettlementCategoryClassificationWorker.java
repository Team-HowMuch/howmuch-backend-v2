package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.ProcessSettlementCategoryJobsUseCase;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.application.port.out.settlement.ClassifySettlementCategoryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementCategoryRulePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementCategoryJobStorePort;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementCategoryClassificationPolicy;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategory;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryFailureCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryJob;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategorySource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * 정산 분류 작업을 처리한다. 규칙 사전 → AI → 미분류 순서다.
 *
 * <p>입금 대조 작업처럼 트랜잭션 밖에서 돈다. AI 호출이 DB 트랜잭션을 붙잡지 않게 하고,
 * 결과는 선점이 유효할 때만 저장한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementCategoryClassificationWorker implements ProcessSettlementCategoryJobsUseCase {
    private final SettlementCategoryJobStorePort jobs;
    private final LoadSettlementPort settlements;
    private final LoadSettlementExpensePort expenses;
    private final LoadSettlementItemPort items;
    private final LoadSettlementCategoryRulePort rules;
    private final LoadCommonCodePort commonCodes;
    private final ClassifySettlementCategoryPort classifier;
    private final SettlementCategoryClassificationPolicy policy;
    private final Clock clock;

    @Override
    public int processDue(int limit) {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Category classification batch size must be 1..100");
        }
        int processed = 0;
        for (int i = 0; i < limit; i++) {
            LocalDateTime now = now();
            var claim = jobs.claimDue(now, now.plus(SettlementCategoryClassificationPolicy.LEASE));
            if (claim.isEmpty()) {
                break;
            }
            process(claim.get());
            processed++;
        }
        return processed;
    }

    private void process(SettlementCategoryJob claim) {
        try {
            var settlement = settlements.findById(claim.settlementId()).orElse(null);
            if (settlement == null) {
                if (!jobs.discard(claim, now())) {
                    leaseLost(claim);
                }
                return;
            }
            var material = policy.material(
                    expenses.findAllBySettlementId(settlement.id()),
                    items.findAllBySettlementId(settlement.id()));
            var candidates = policy.candidates(commonCodes.findActiveByGroupCode(SettlementCategory.GROUP_CODE));
            Set<String> codes = SettlementCategoryClassificationPolicy.codes(candidates);
            var rule = policy.matchRule(material, rules.findActive(), codes);
            if (rule.isPresent()) {
                save(claim, rule.get().categoryCode(), SettlementCategorySource.RULE);
                return;
            }
            if (material.empty() || candidates.isEmpty() || !classifier.available()) {
                uncategorized(claim);
                return;
            }
            var result = classifier.classify(new ClassifySettlementCategoryPort.Request(
                    material.merchantNames(), material.itemNames(), candidates));
            switch (result.outcome()) {
                case CLASSIFIED, UNKNOWN -> policy.acceptAi(result, codes).ifPresentOrElse(
                        code -> save(claim, code, SettlementCategorySource.AI),
                        () -> uncategorized(claim));
                case PROVIDER_ERROR -> fail(claim, SettlementCategoryFailureCode.PROVIDER_ERROR);
                case INVALID_RESPONSE -> fail(claim, SettlementCategoryFailureCode.INVALID_RESPONSE);
            }
        } catch (RuntimeException failure) {
            log.warn("Settlement category classification failed: settlementId={}, attempt={}, error={}",
                    claim.settlementId(), claim.attemptCount(), failure.getClass().getSimpleName());
            fail(claim, SettlementCategoryFailureCode.PROVIDER_ERROR);
        }
    }

    private void save(SettlementCategoryJob claim, String code, SettlementCategorySource source) {
        if (!jobs.classify(claim, code, source, now())) {
            leaseLost(claim);
        }
    }

    private void uncategorized(SettlementCategoryJob claim) {
        save(claim, SettlementCategory.UNCATEGORIZED, SettlementCategorySource.UNCATEGORIZED);
    }

    private void fail(SettlementCategoryJob claim, SettlementCategoryFailureCode code) {
        LocalDateTime now = now();
        var next = policy.nextAttempt(claim, now);
        boolean saved = next.isPresent()
                ? jobs.retry(claim, next.get(), code, now)
                : jobs.giveUp(claim, code, now);
        if (!saved) {
            leaseLost(claim);
            return;
        }
        log.info("Settlement category classification attempt failed: settlementId={}, attempt={}, failure={}, retry={}",
                claim.settlementId(), claim.attemptCount(), code, next.isPresent());
    }

    // 처리가 선점 시간보다 길어져 다른 폴링이 작업을 회수한 경우. 회수한 쪽이 이어서 처리한다.
    private void leaseLost(SettlementCategoryJob claim) {
        log.info("Settlement category result skipped because the lease ended: settlementId={}, attempt={}",
                claim.settlementId(), claim.attemptCount());
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
