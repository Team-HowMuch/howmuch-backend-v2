package com.amateur.howmuchbackend.application.account.policy;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionResult;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AccountTransactionDisplayNormalizer {
    private static final Set<String> TRANSACTION_LABELS = Set.of(
            "실시간이체", "이체", "송금", "입금", "출금", "거래", "결제", "체크", "체크카드",
            "신용카드", "카드", "ATM", "현금", "수수료"
    );
    private static final Map<String, Set<String>> BANK_MARKERS = Map.of(
            "0003", Set.of("기업", "기업은행", "IBK", "IBK기업"),
            "0004", Set.of("국민", "국민은행", "KB", "KB국민"),
            "0011", Set.of("농협", "농협은행", "NH"),
            "0020", Set.of("우리", "우리은행"),
            "0037", Set.of("전북", "전북은행"),
            "0039", Set.of("경남", "경남은행"),
            "0045", Set.of("새마을", "새마을금고", "MG"),
            "0071", Set.of("우체국"),
            "0081", Set.of("하나", "하나은행", "KEB", "KEB하나"),
            "0088", Set.of("신한", "신한은행")
    );

    public AccountTransactionResult.Display normalize(
            String organization,
            String description,
            AccountTransactionType type
    ) {
        String normalized = normalizeText(description);
        if (normalized == null || type == null) {
            return other(description);
        }
        String title = Arrays.stream(normalized.split(" "))
                .filter(token -> !isNumericAccount(token))
                .filter(token -> !TRANSACTION_LABELS.contains(token))
                .filter(token -> !isBankMarker(organization, token))
                .filter(token -> !isBankMethod(token))
                .reduce((left, right) -> left + " " + right)
                .orElse(normalized)
                .strip();
        return new AccountTransactionResult.Display(
                title.isBlank() ? normalized : title,
                kind(normalized)
        );
    }

    private AccountTransactionResult.Display other(String description) {
        String title = normalizeText(description);
        return new AccountTransactionResult.Display(title, AccountTransactionResult.Display.Kind.OTHER);
    }

    private AccountTransactionResult.Display.Kind kind(String description) {
        if (description.contains("수수료")) {
            return AccountTransactionResult.Display.Kind.FEE;
        }
        if (containsAny(description, List.of("ATM", "현금", "CD기"))) {
            return AccountTransactionResult.Display.Kind.ATM;
        }
        if (containsCardMethod(description)
                || containsAny(description, List.of("체크카드", "신용카드", "카드", "페이", "결제"))) {
            return AccountTransactionResult.Display.Kind.CARD;
        }
        if (containsAny(description, List.of("이체", "송금", "입금", "출금"))) {
            return AccountTransactionResult.Display.Kind.TRANSFER;
        }
        return AccountTransactionResult.Display.Kind.OTHER;
    }

    private boolean containsCardMethod(String description) {
        return Arrays.stream(description.split(" ")).anyMatch(this::isCardMethod);
    }

    private boolean containsAny(String value, List<String> candidates) {
        return candidates.stream().anyMatch(value::contains);
    }

    private boolean isBankMarker(String organization, String token) {
        return BANK_MARKERS.getOrDefault(organization, Set.of()).contains(token);
    }

    private boolean isBankMethod(String token) {
        return token.matches("(?i)^(NH|IBK|KB|KEB|KDB|SC|MG)(체크|신용|카드)?$");
    }

    private boolean isCardMethod(String token) {
        return token.matches("(?i)^(NH|IBK|KB|KEB|KDB|SC|MG)(체크|신용|카드)$");
    }

    private boolean isNumericAccount(String token) {
        String digits = token.replaceAll("\\D", "");
        return digits.length() >= 4 && digits.length() == token.length();
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replaceAll("(?U)\\s+", " ")
                .strip();
    }
}
