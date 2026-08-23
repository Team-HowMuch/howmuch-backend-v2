package com.amateur.howmuchbackend.adapter.out.persistence.settlement;

import com.amateur.howmuchbackend.adapter.out.persistence.settlement.mapper.SettlementExpenseMapper;
import com.amateur.howmuchbackend.adapter.out.persistence.settlement.repository.SettlementExpenseJpaRepository;
import com.amateur.howmuchbackend.application.port.out.settlement.DeleteSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementExpensePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementExpensePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpense;
import com.amateur.howmuchbackend.domain.settlement.SettlementExpenseSource;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class SettlementExpensePersistenceAdapter implements
        LoadSettlementExpensePort,
        SaveSettlementExpensePort,
        DeleteSettlementExpensePort {
    private final SettlementExpenseJpaRepository settlementExpenseJpaRepository;

    @Override
    public List<SettlementExpense> findAllBySettlementId(UUID settlementId) {
        return settlementExpenseJpaRepository
                .findAllBySettlementIdOrderByDisplayOrderAsc(settlementId)
                .stream()
                .map(SettlementExpenseMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<SettlementExpense> findBySettlementIdAndId(
            UUID settlementId,
            UUID expenseId
    ) {
        return settlementExpenseJpaRepository.findBySettlementIdAndId(settlementId, expenseId)
                .map(SettlementExpenseMapper::toDomain);
    }

    @Override
    public int nextDisplayOrder(UUID settlementId) {
        return settlementExpenseJpaRepository
                .findFirstBySettlementIdOrderByDisplayOrderDesc(settlementId)
                .map(expense -> Math.incrementExact(expense.getDisplayOrder()))
                .orElse(0);
    }

    @Override
    public long totalAmount(UUID settlementId) {
        return settlementExpenseJpaRepository.sumAmountBySettlementId(settlementId);
    }

    @Override
    public boolean existsBySettlementIdAndSourceAndSourceReference(
            UUID settlementId,
            SettlementExpenseSource source,
            String sourceReference
    ) {
        return settlementExpenseJpaRepository
                .existsBySettlementIdAndSourceAndSourceReference(
                        settlementId,
                        source,
                        sourceReference
                );
    }

    @Override
    public SettlementExpense save(SettlementExpense expense) {
        try {
            return SettlementExpenseMapper.toDomain(
                    settlementExpenseJpaRepository.saveAndFlush(
                            SettlementExpenseMapper.toEntity(expense)
                    )
            );
        } catch (DataIntegrityViolationException exception) {
            if (hasConstraint(
                    exception,
                    "uk_settlement_expenses_settlement_source_reference"
            )) {
                throw ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_EXPENSE_ALREADY_IMPORTED,
                        exception
                );
            }
            throw exception;
        }
    }

    @Override
    public SettlementExpense update(SettlementExpense expense) {
        var entity = settlementExpenseJpaRepository
                .findBySettlementIdAndId(expense.settlementId(), expense.id())
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_EXPENSE_NOT_FOUND
                ));
        entity.updateFrom(expense);
        return SettlementExpenseMapper.toDomain(settlementExpenseJpaRepository.saveAndFlush(entity));
    }

    @Override
    public void delete(SettlementExpense expense) {
        settlementExpenseJpaRepository.deleteById(expense.id());
        settlementExpenseJpaRepository.flush();
    }

    private boolean hasConstraint(Throwable throwable, String constraintName) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ConstraintViolationException constraintViolation
                    && constraintName.equals(constraintViolation.getConstraintName())) {
                return true;
            }
            if (current.getMessage() != null && current.getMessage().contains(constraintName)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
