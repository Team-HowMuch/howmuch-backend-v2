package com.amateur.howmuchbackend.application.settlement.netting.policy;

import com.amateur.howmuchbackend.application.port.out.settlement.netting.LoadGroupNettingPort.Transfer;
import com.amateur.howmuchbackend.domain.account.Transaction;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class NettedDepositMatchingPolicy {
    private static final Pattern REFERENCE =
            Pattern.compile("(?<![A-Z0-9])H[0-9A-F]{7}(?![A-Z0-9])");

    public boolean hasKnownReference(Transaction transaction, List<Transfer> transfers) {
        var references = references(transaction);
        return transfers.stream().anyMatch(t -> references.contains(t.reference()));
    }

    private Set<String> references(Transaction transaction) {
        String text =
                String.join(
                                " ",
                                Objects.toString(transaction.description(), ""),
                                Objects.toString(transaction.description1(), ""),
                                Objects.toString(transaction.description2(), ""),
                                Objects.toString(transaction.description3(), ""),
                                Objects.toString(transaction.description4(), ""))
                        .toUpperCase(Locale.ROOT);
        Set<String> references = new HashSet<>();
        var matcher = REFERENCE.matcher(text);
        while (matcher.find()) references.add(matcher.group());
        return references;
    }

    public Optional<UUID> match(Transaction transaction, List<Transfer> candidates) {
        if (transaction.type()
                != com.amateur.howmuchbackend.domain.account.AccountTransactionType.INCOME)
            return Optional.empty();
        Set<String> references = references(transaction);
        if (references.size() != 1) return Optional.empty();
        long amount;
        try {
            amount = transaction.amount().longValueExact();
        } catch (ArithmeticException invalid) {
            return Optional.empty();
        }
        var matches =
                candidates.stream()
                        .filter(
                                t ->
                                        t.accountId() == transaction.accountId()
                                                && references.contains(t.reference())
                                                && !transaction
                                                        .transactedAt()
                                                        .isBefore(t.activatedAt())
                                                && amount > 0
                                                && amount <= t.remainingAmount())
                        .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst().id()) : Optional.empty();
    }
}
