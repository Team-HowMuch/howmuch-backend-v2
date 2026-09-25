package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.account.Transaction;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.regex.Pattern;

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
        String fullName = description.replace(" ", "");
        // 접미어의 공백 경계는 이름 내부 공백을 제거하기 전에 확인한다.
        String nameBeforeDeposit = description.endsWith(" 입금")
                ? description.substring(0, description.length() - " 입금".length()).replace(" ", "")
                : "";
        UUID onlyMatch = null;
        int matchCount = 0;
        UUID onlyExactMatch = null;
        int exactMatchCount = 0;
        for (PayableShare share : payableShares) {
            if (transaction.transactedAt().isBefore(share.snapshotCreatedAt())
                    || share.remainingAmount() < amount
                    || !matchesName(share.payerName(), description, fullName, nameBeforeDeposit)) {
                continue;
            }
            matchCount++;
            onlyMatch = share.shareId();
            if (share.remainingAmount() == amount) {
                exactMatchCount++;
                onlyExactMatch = share.shareId();
            }
        }
        if (exactMatchCount > 0) {
            return exactMatchCount == 1 ? Optional.of(onlyExactMatch) : Optional.empty();
        }
        return matchCount == 1 ? Optional.of(onlyMatch) : Optional.empty();
    }

    private boolean matchesName(
            String payerName,
            String description,
            String fullName,
            String nameBeforeDeposit
    ) {
        if (payerName == null) {
            return false;
        }
        String normalizedName = normalize(payerName);
        String name = normalizedName.replace(" ", "");
        return !name.isEmpty()
                && (name.equals(fullName)
                || name.equals(nameBeforeDeposit)
                || containsWholeName(description, normalizedName));
    }

    private boolean containsWholeName(String description, String normalizedName) {
        StringJoiner namePattern = new StringJoiner("\\s*");
        for (String namePart : normalizedName.split(" ")) {
            namePattern.add(Pattern.quote(namePart));
        }
        return Pattern.compile("(?<![\\p{L}\\p{N}])"
                        + namePattern
                        + "(?![\\p{L}\\p{N}])")
                .matcher(description)
                .find();
    }

    private String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("(?U)\\s+", " ")
                .strip();
    }

    public record PayableShare(
            UUID shareId,
            String payerName,
            long remainingAmount,
            LocalDateTime snapshotCreatedAt
    ) {
    }
}
