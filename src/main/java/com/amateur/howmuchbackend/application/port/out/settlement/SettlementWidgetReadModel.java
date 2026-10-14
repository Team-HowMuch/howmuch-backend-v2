package com.amateur.howmuchbackend.application.port.out.settlement;

import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import java.math.BigInteger;
import java.util.UUID;

/** 생성자에게는 자기 몫을 제외한 참여자 전체, 참여자에게는 본인 사실만 반환한다. */
public record SettlementWidgetReadModel(
        UUID settlementId, String title, long totalAmount, boolean creator,
        SettlementStatus status, SettlementAllocationType allocationType,
        UUID creatorParticipantId, UUID participantId, Long shareAmount,
        BigInteger confirmedAmount, boolean submitted, boolean receiptAdjustmentPending
) {}
