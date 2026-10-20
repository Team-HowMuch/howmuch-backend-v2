package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.application.port.in.settlement.command.WriteRecurringSettlementCommand;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlement;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurringSettlementStatusCode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecurringSettlementFormPolicy {
    private final DutchPayAllocationPolicy dutchPay;

    public List<RecurringSettlement.Participant> allocations(Long creator, WriteRecurringSettlementCommand command) {
        var ids = command.participantUserIds();
        if (ids == null || ids.size() < 2 || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size() || !ids.contains(creator)
                || command.amount() < ids.size() || command.receivingAccountId() == null || command.receivingAccountId() <= 0) {
            throw ApplicationException.of(RecurringSettlementStatusCode.INVALID_RECURRING_SETTLEMENT_FORM);
        }
        // Same equal-split rule as DutchPayAllocationPolicy: the creator bears the won remainder.
        var result = new ArrayList<RecurringSettlement.Participant>();
        for (int i = 0; i < ids.size(); i++) {
            result.add(new RecurringSettlement.Participant(ids.get(i), dutchPay.equalAmount(command.amount(), ids.size(), ids.get(i).equals(creator)), i));
        }
        return List.copyOf(result);
    }

}
