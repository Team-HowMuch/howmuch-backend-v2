package com.amateur.howmuchbackend.application.settlement.netting.calculator;

import com.amateur.howmuchbackend.domain.settlement.netting.*;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class NettingResultValidator {
    public void validate(List<NettingDebt> debts, NettingCalculation result) {
        validate(NettingCalculationContext.from(debts), result);
    }

    public void validate(NettingCalculationContext context, NettingCalculation result) {
        validateCandidate(context, result);
        if (result.payments().size() > context.baseline().size()) {
            throw new IllegalArgumentException("Netting must not increase transfer count");
        }
    }

    public void validateCandidate(NettingCalculationContext context, NettingCalculation result) {
        if (result.originalTransferCount() != context.baseline().size()
                || !context.balances().equals(NettingBalanceSupport.balances(result.payments()))) {
            throw new IllegalArgumentException(
                    "Netting must preserve balances and not increase transfer count");
        }
        Set<Long> participants = new HashSet<>();
        context.debts()
                .forEach(
                        d -> {
                            participants.add(d.payerId());
                            participants.add(d.receiverId());
                        });
        Set<String> pairs = new HashSet<>();
        for (var payment : result.payments()) {
            if (!participants.contains(payment.payerId())
                    || !participants.contains(payment.receiverId()))
                throw new IllegalArgumentException("Payment includes an unrelated participant");
            if (!pairs.add(payment.payerId() + ":" + payment.receiverId())) {
                throw new IllegalArgumentException("Duplicate final payment");
            }
        }
    }
}
