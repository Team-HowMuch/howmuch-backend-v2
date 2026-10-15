package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.in.settlement.ChangeMySettlementCategoryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ChangeMySettlementCategoryCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveMySettlementCategoryPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 내 카테고리 수정(STL-008-003). 정산 상세와 같이 생성자 또는 회원으로 연결된 참여자만 바꿀 수 있고,
 * 정산 상태와 관계없이 바꿀 수 있다. 선택지는 카테고리 목록 API의 정산용 카테고리(미분류 포함)다.
 */
@Service
@RequiredArgsConstructor
public class MySettlementCategoryService implements ChangeMySettlementCategoryUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final SettlementCategoryCatalog catalog;
    private final SaveMySettlementCategoryPort saveMySettlementCategoryPort;

    @Override
    @Transactional
    public SettlementCategoryResult change(UUID settlementId, ChangeMySettlementCategoryCommand command) {
        Long actorId = currentActorPort.getCurrentActor().userId();
        Settlement settlement = loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        if (!settlement.isCreatedBy(actorId)
                && loadSettlementParticipantPort.findAllBySettlementId(settlementId).stream()
                        .noneMatch(participant -> participant.isLinkedTo(actorId))) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        SettlementCategoryResult category = catalog.byCode().get(command.categoryCode());
        if (category == null) {
            throw ApplicationException.of(SettlementStatusCode.INVALID_SETTLEMENT_CATEGORY);
        }
        saveMySettlementCategoryPort.save(
                settlementId, actorId, category.code(), LocalDateTime.now(ZoneOffset.UTC));
        return category;
    }
}
