package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryFailureCode;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategoryJob;
import com.amateur.howmuchbackend.domain.settlement.SettlementCategorySource;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 정산 분류 작업 저장소.
 *
 * <p>정산당 작업은 하나다. 작업을 만드는 메서드는 이미 있으면 아무것도 하지 않는다.
 * 결과를 저장하는 메서드는 선점이 아직 유효할 때만 반영하고, 반영했는지를 돌려준다.
 */
public interface SettlementCategoryJobStorePort {
    void schedule(UUID settlementId, LocalDateTime now);

    void saveUncategorized(UUID settlementId, LocalDateTime now);

    /** 만료된 선점을 먼저 회수하고, 대기 작업 하나를 선점한다. */
    Optional<SettlementCategoryJob> claimDue(LocalDateTime now, LocalDateTime leaseUntil);

    boolean classify(SettlementCategoryJob claim, String categoryCode, SettlementCategorySource source,
            LocalDateTime now);

    boolean retry(SettlementCategoryJob claim, LocalDateTime nextAttemptAt, SettlementCategoryFailureCode failureCode,
            LocalDateTime now);

    /** 시도를 다 쓴 작업을 실패 사유와 함께 미분류로 끝낸다. */
    boolean giveUp(SettlementCategoryJob claim, SettlementCategoryFailureCode failureCode, LocalDateTime now);

    /** 분류할 정산이 없어진 작업을 지운다. */
    boolean discard(SettlementCategoryJob claim, LocalDateTime now);
}
