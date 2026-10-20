package com.amateur.howmuchbackend.application.port.out.settlement.netting;

import com.amateur.howmuchbackend.application.settlement.netting.calculator.NettingCalculatorSelection;
import com.amateur.howmuchbackend.domain.settlement.netting.*;

import java.util.*;

/** Internal calculation snapshot; no account numbers or bank credentials. */
public record NettingInput(
        long groupId,
        long ownerId,
        long version,
        UUID predecessorNettingId,
        List<Source> sources,
        List<CarriedTransfer> carriedTransfers,
        List<Long> requiredUserIds,
        List<Receiver> receivers,
        List<MonitoringAccount> monitoringAccounts,
        NettingCalculation calculation,
        NettingCalculatorSelection calculatorSelection) {
    public NettingInput {
        sources = List.copyOf(sources);
        carriedTransfers = List.copyOf(carriedTransfers);
        requiredUserIds = List.copyOf(requiredUserIds);
        receivers = List.copyOf(receivers);
        monitoringAccounts = List.copyOf(monitoringAccounts);
    }

    public int compressionTargetCount() {
        return Math.addExact(sources.size(), predecessorNettingId == null ? 0 : 1);
    }

    public List<NettingDebt> debts() {
        var result = new ArrayList<NettingDebt>();
        sources.forEach(s -> result.addAll(s.debts()));
        carriedTransfers.stream()
                .filter(t -> t.remainingAmount() > 0)
                .forEach(
                        t ->
                                result.add(
                                        new NettingDebt(
                                                predecessorNettingId,
                                                t.id(),
                                                t.payerId(),
                                                t.receiverId(),
                                                t.amount(),
                                                t.confirmedAmount())));
        return List.copyOf(result);
    }

    public record Source(
            UUID settlementId,
            long revision,
            long creatorId,
            List<Party> parties,
            List<NettingDebt> debts,
            List<String> depositKeys,
            long receivingAccountId,
            String receivingAccountDigest) {
        public Source {
            parties = List.copyOf(parties);
            debts = List.copyOf(debts);
            depositKeys = List.copyOf(depositKeys);
        }
    }

    public record Party(UUID participantId, Long userId, String source, Long sourceGroupId) {}

    public record CarriedTransfer(
            UUID id, long payerId, long receiverId, long amount, long confirmedAmount) {
        public long remainingAmount() {
            return Math.subtractExact(amount, confirmedAmount);
        }
    }

    public record MonitoringAccount(
            long userId,
            long accountId,
            long connectionId,
            String organization,
            String accountIdentityDigest) {}

    public record Receiver(
            long userId,
            long accountId,
            long connectionId,
            String organization,
            String maskedNumber,
            String accountIdentityDigest) {}
}
