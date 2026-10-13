package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementItemPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementItemAllocationStorePort;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerSettlementsPort.TreasurerSettlementRecord;
import com.amateur.howmuchbackend.application.settlement.policy.SettlementItemAllocationPolicy;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.treasurer.SettlementDirection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 품목 선택이 끝나지 않은 품목별 정산에서 생성자가 받을 금액을 구한다.
 *
 * <p>treasurer 조회는 share 합계로 금액을 구한다. 품목별 정산은 참여자 전원이 제출하기 전까지
 * share가 만들어지지 않아 {@code REQUESTED} 동안 합계가 0원이 된다. 정산 상세는 이 구간에
 * 영수증 총액에서 생성자 본인 몫을 뺀 값을 쓰므로, 같은 정책 메서드를 호출해 값을 맞춘다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TreasurerRequestedReceivableResolver {
    private final LoadSettlementPort loadSettlementPort;
    private final LoadSettlementParticipantPort loadSettlementParticipantPort;
    private final LoadSettlementItemPort loadSettlementItemPort;
    private final LoadSettlementExpensePort loadSettlementExpensePort;
    private final SettlementItemAllocationStorePort itemAllocationStorePort;
    private final SettlementItemAllocationPolicy itemAllocationPolicy;

    /**
     * share가 아직 없는 구간의 받을 정산인지. 정산 상세가 같은 조건({@code ITEM_ALLOCATION}이고 {@code REQUESTED})에서
     * 총액 기준 금액을 쓴다.
     */
    public boolean appliesTo(TreasurerSettlementRecord record) {
        return record.direction() == SettlementDirection.RECEIVE
                && record.allocationType() == SettlementAllocationType.ITEM_ALLOCATION
                && record.status() == SettlementStatus.REQUESTED;
    }

    /**
     * 읽기 전용 트랜잭션을 이 메서드 안에서만 연다. 호출자(질문 흐름)는 LLM 호출 동안 커넥션을 쥐지 않기 위해
     * 트랜잭션을 열지 않는다.
     *
     * @return 정산이 없거나 계산할 수 없으면 비어 있다. 호출자는 기존 금액을 그대로 쓴다.
     */
    @Transactional(readOnly = true)
    public Optional<Long> resolve(UUID settlementId) {
        Optional<Settlement> found = loadSettlementPort.findById(settlementId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Settlement settlement = found.get();
        try {
            UUID creatorParticipantId = loadSettlementParticipantPort.findAllBySettlementId(settlementId).stream()
                    .filter(participant -> participant.isLinkedTo(settlement.creatorId()))
                    .map(SettlementParticipant::id)
                    .findFirst()
                    .orElse(null);
            if (creatorParticipantId == null) {
                return Optional.of(settlement.totalAmount());
            }
            List<SettlementExpense> expenses = loadSettlementExpensePort.findAllBySettlementId(settlementId);
            long receiptDiscount = expenses.stream()
                    .mapToLong(SettlementExpense::discount)
                    .reduce(0L, Math::addExact);
            long deliveryFee = expenses.stream()
                    .mapToLong(SettlementExpense::deliveryFee)
                    .reduce(0L, Math::addExact);
            return Optional.of(itemAllocationPolicy.calculateRequestedReceivableAmount(
                    settlement.totalAmount(),
                    loadSettlementItemPort.findAllBySettlementId(settlementId),
                    itemAllocationStorePort.findAllSelections(settlementId),
                    creatorParticipantId,
                    receiptDiscount,
                    deliveryFee
            ));
        } catch (ApplicationException | ArithmeticException exception) {
            // 저장된 데이터가 서로 맞지 않는 정산 한 건 때문에 AI 총무 전체가 실패하지 않게 한다.
            log.warn(
                    "Failed to resolve requested receivable amount for treasurer. settlementId={} failureType={}",
                    settlementId,
                    exception.getClass().getSimpleName()
            );
            return Optional.empty();
        }
    }
}
