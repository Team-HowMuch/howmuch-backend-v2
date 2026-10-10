package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementUnpaidSummaryJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementUnpaidSummaryPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementUnpaidSummaryReadModel;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementAllocationType;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SettlementUnpaidSummaryPersistenceAdapter implements LoadSettlementUnpaidSummaryPort {
    private final SettlementUnpaidSummaryJpaRepository repository;

    @Override
    public List<SettlementUnpaidSummaryReadModel> findAllByCreatorId(Long creatorId) {
        return repository.findAllByCreatorId(creatorId).stream()
                .map(this::toReadModel)
                .toList();
    }

    private SettlementUnpaidSummaryReadModel toReadModel(
            SettlementUnpaidSummaryJpaRepository.UnpaidSummaryProjection projection
    ) {
        try {
            return new SettlementUnpaidSummaryReadModel(
                    projection.getSettlementId(),
                    SettlementStatus.valueOf(projection.getStatus()),
                    projection.getAllocationType() == null
                            ? null
                            : SettlementAllocationType.valueOf(projection.getAllocationType()),
                    projection.getParticipantId(),
                    projection.getShareAmount(),
                    projection.getConfirmedAmount().toBigIntegerExact()
            );
        } catch (ArithmeticException exception) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_DEPOSIT_DATA_NOT_FOUND, exception);
        }
    }
}
