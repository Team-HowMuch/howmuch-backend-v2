package com.amateur.howmuchbackend.application.port.in.settlement;

import com.amateur.howmuchbackend.application.port.in.settlement.command.SaveSettlementItemSelectionCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementItemAllocationResult;

import java.util.UUID;

/**
 * 참여자 본인이 앱에서 자기 몫을 다룬다. 링크 토큰 없이 로그인만으로 쓴다.
 * 생성자 전용 경로와 달리 요청 이후 상태에서만 동작한다.
 */
public interface ResolveMyItemAllocationUseCase {
    SettlementItemAllocationResult getMyAllocation(UUID settlementId);

    SettlementItemAllocationResult saveMySelection(
            UUID settlementId,
            UUID itemId,
            SaveSettlementItemSelectionCommand command
    );

    SettlementItemAllocationResult removeMySelection(UUID settlementId, UUID itemId);

    SettlementItemAllocationResult submitMySelection(UUID settlementId);
}
