package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 반복 설정 스냅샷 조회. 도래 후보는 fresh(retry_at IS NULL)와 retry(retry_at 도래) 두 인덱스 가지를
 * 합쳐 오래된 순서로 돌려준다. {@code scheduledFor}는 항상 원래 예정 시각({@code next_run_at})이다.
 *
 * <p>한 번의 cursor 스캔 주기 동안 후보의 {@code next_run_at}은 {@code scheduledThrough}(주기 시작 시각)로
 * 고정된다. 새로 도래하는 행이 스캔 꼬리를 무한히 늘리지 못하게 하려는 상한이며, retry 자격만 실제
 * {@code now}로 판정한다. {@code scheduledThrough}는 {@code now} 이하이고, cursor가 없는 새 주기에서 다시
 * 현재 시각으로 잡힌다.
 */
public interface LoadRecurringSettlementPort {
    Optional<RecurringSettlement> findById(UUID id);

    Optional<RecurringSettlement> findByIdForUpdate(UUID id);

    Optional<RecurringSettlement> findBySourceSettlementId(UUID sourceId);

    List<DueOccurrence> findDue(LocalDateTime now, LocalDateTime scheduledThrough, DueOccurrence cursor, int limit);

    record DueOccurrence(UUID id, LocalDateTime scheduledFor) {
    }
}
