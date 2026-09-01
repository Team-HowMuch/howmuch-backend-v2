package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record SettlementItemSelection(
        UUID id,
        UUID settlementId,
        UUID itemId,
        UUID participantId,
        SettlementItemAllocationFraction fraction,
        int units,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static final int MIN_UNITS = 1;

    public SettlementItemSelection {
        Objects.requireNonNull(id, "품목 선택 ID는 필수입니다.");
        Objects.requireNonNull(settlementId, "정산 ID는 필수입니다.");
        Objects.requireNonNull(itemId, "정산 품목 ID는 필수입니다.");
        Objects.requireNonNull(participantId, "정산 참여자 ID는 필수입니다.");
        Objects.requireNonNull(fraction, "품목 선택 비율은 필수입니다.");
        Objects.requireNonNull(createdAt, "품목 선택 생성 시각은 필수입니다.");
        Objects.requireNonNull(updatedAt, "품목 선택 수정 시각은 필수입니다.");
        if (updatedAt.isBefore(createdAt)) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION
            );
        }
        validateUnits(fraction, units);
    }

    public static SettlementItemSelection create(
            UUID settlementId,
            UUID itemId,
            UUID participantId,
            SettlementItemAllocationFraction fraction,
            int units,
            LocalDateTime now
    ) {
        Objects.requireNonNull(now, "품목 선택 생성 시각은 필수입니다.");
        return new SettlementItemSelection(
                UUID.randomUUID(),
                settlementId,
                itemId,
                participantId,
                fraction,
                units,
                now,
                now
        );
    }

    public SettlementItemSelection change(
            SettlementItemAllocationFraction changedFraction,
            int changedUnits,
            LocalDateTime now
    ) {
        return new SettlementItemSelection(
                id,
                settlementId,
                itemId,
                participantId,
                changedFraction,
                changedUnits,
                createdAt,
                Objects.requireNonNull(now, "품목 선택 수정 시각은 필수입니다.")
        );
    }

    /** 품목 한 개를 WHOLE_UNITS로 볼 때 이 선택이 차지하는 단위. */
    public int allocatedUnits() {
        return Math.multiplyExact(fraction.units(), units);
    }

    public long amountFor(SettlementItem item) {
        if (item == null
                || !settlementId.equals(item.settlementId())
                || !itemId.equals(item.id())) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION
            );
        }
        return Math.multiplyExact(fraction.amountOf(item.amount()), units);
    }

    private static void validateUnits(SettlementItemAllocationFraction fraction, int units) {
        if (units < MIN_UNITS
                || Math.multiplyExact(fraction.units(), units)
                > SettlementItemAllocationFraction.WHOLE_UNITS) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_ITEM_SELECTION_UNITS
            );
        }
    }
}
