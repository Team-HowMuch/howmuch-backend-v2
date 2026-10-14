package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementWidgetJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementWidgetPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementWidgetReadModel;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SettlementWidgetPersistenceAdapter implements LoadSettlementWidgetPort {
    private final SettlementWidgetJpaRepository repository;

    @Override
    public List<SettlementWidgetReadModel> findAllInProgress(Long userId) {
        return repository.findAllInProgress(userId).stream().map(this::map).toList();
    }

    private SettlementWidgetReadModel map(SettlementWidgetJpaRepository.WidgetProjection row) {
        try {
            return new SettlementWidgetReadModel(row.getSettlementId(), row.getTitle(), row.getTotalAmount(),
                    row.getCreator(), SettlementStatus.valueOf(row.getStatus()),
                    row.getAllocationType() == null ? null : SettlementAllocationType.valueOf(row.getAllocationType()),
                    row.getCreatorParticipantId(), row.getParticipantId(), row.getShareAmount(),
                    row.getConfirmedAmount().toBigIntegerExact(), row.getSubmitted(), row.getReceiptAdjustmentPending());
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND, exception);
        }
    }
}
