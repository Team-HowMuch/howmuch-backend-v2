package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
public class SettlementDepositMatchingPolicy {
    public Optional<UUID> match(
            Transaction transaction,
            List<PayableShare> payableShares
    ) {
        if (transaction.type() != AccountTransactionType.INCOME
                || transaction.description() == null
                || transaction.description().isBlank()) {
            return Optional.empty();
        }
        long amount;
        try {
            amount = transaction.amount().longValueExact();
        } catch (ArithmeticException exception) {
            return Optional.empty();
        }
        if (amount <= 0) {
            return Optional.empty();
        }

        String description = normalize(transaction.description());
        List<UUID> matches = payableShares.stream()
                .filter(share -> !transaction.transactedAt().isBefore(share.snapshotCreatedAt()))
                .filter(share -> share.remainingAmount() >= amount)
                .filter(share -> description.contains(normalize(share.payerName())))
                .map(PayableShare::shareId)
                .limit(2)
                .toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "");
    }

    public record PayableShare(
            UUID shareId,
            String payerName,
            long remainingAmount,
            LocalDateTime snapshotCreatedAt
    ) {
    }
}
