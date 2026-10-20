package com.amateur.howmuchbackend.application.settlement.netting.calculator;

import com.amateur.howmuchbackend.domain.settlement.netting.NettingDebt;
import com.amateur.howmuchbackend.domain.settlement.netting.NettingPayment;

import java.util.*;

final class NettingBalanceSupport {
    private NettingBalanceSupport() {}

    static Map<Long, Long> balances(List<NettingPayment> payments) {
        Map<Long, Long> balances = new TreeMap<>();
        for (var payment : payments) {
            balances.merge(payment.payerId(), Math.negateExact(payment.amount()), Math::addExact);
            balances.merge(payment.receiverId(), payment.amount(), Math::addExact);
        }
        balances.values().removeIf(value -> value == 0);
        return balances;
    }

    static List<NettingPayment> baseline(List<NettingDebt> debts) {
        record Pair(long payer, long receiver) {}
        Map<Pair, Long> amounts = new HashMap<>();
        for (var debt : debts) {
            if (debt.remainingAmount() > 0) {
                amounts.merge(
                        new Pair(debt.payerId(), debt.receiverId()),
                        debt.remainingAmount(),
                        Math::addExact);
            }
        }
        return amounts.entrySet().stream()
                .map(
                        entry ->
                                new NettingPayment(
                                        entry.getKey().payer(),
                                        entry.getKey().receiver(),
                                        entry.getValue()))
                .sorted(
                        Comparator.comparingLong(NettingPayment::payerId)
                                .thenComparingLong(NettingPayment::receiverId))
                .toList();
    }
}
