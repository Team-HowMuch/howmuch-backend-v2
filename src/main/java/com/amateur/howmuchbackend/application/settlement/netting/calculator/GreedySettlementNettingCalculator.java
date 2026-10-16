package com.amateur.howmuchbackend.application.settlement.netting.calculator;

import com.amateur.howmuchbackend.domain.settlement.netting.*;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GreedySettlementNettingCalculator implements SettlementNettingCalculator {
    private record Balance(long userId, long amount) {}

    public static final NettingAlgorithmKey KEY = new NettingAlgorithmKey("GREEDY_NET_BALANCE", 1);

    @Override
    public NettingAlgorithmKey key() {
        return KEY;
    }

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public boolean supports(NettingCalculationContext context) {
        return true;
    }

    @Override
    public List<NettingPayment> calculate(NettingCalculationContext context) {
        var balances = context.balances();
        Comparator<Balance> order =
                Comparator.comparingLong(Balance::amount)
                        .reversed()
                        .thenComparingLong(Balance::userId);
        PriorityQueue<Balance> payers = new PriorityQueue<>(order);
        PriorityQueue<Balance> receivers = new PriorityQueue<>(order);
        balances.forEach(
                (user, balance) -> {
                    if (balance < 0) payers.add(new Balance(user, Math.negateExact(balance)));
                    if (balance > 0) receivers.add(new Balance(user, balance));
                });
        List<NettingPayment> greedy = new ArrayList<>();
        while (!payers.isEmpty() && !receivers.isEmpty()) {
            Balance payer = payers.remove();
            Balance receiver = receivers.remove();
            long amount = Math.min(payer.amount(), receiver.amount());
            greedy.add(new NettingPayment(payer.userId(), receiver.userId(), amount));
            if (payer.amount() > amount)
                payers.add(new Balance(payer.userId(), payer.amount() - amount));
            if (receiver.amount() > amount)
                receivers.add(new Balance(receiver.userId(), receiver.amount() - amount));
        }
        return List.copyOf(greedy);
    }
}
