package com.amateur.howmuchbackend.adapter.out.transfer;

import com.amateur.howmuchbackend.application.port.out.settlement.SettlementTransferLinkPort;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

@Component
public class TossSettlementTransferLinkAdapter implements SettlementTransferLinkPort {
    private static final String PROVIDER = "TOSS";
    private static final Map<String, String> BANK_NAMES = Map.ofEntries(
            Map.entry("0002", "KDB산업은행"),
            Map.entry("0003", "IBK기업은행"),
            Map.entry("0004", "KB국민은행"),
            Map.entry("0007", "수협은행"),
            Map.entry("0011", "NH농협은행"),
            Map.entry("0020", "우리은행"),
            Map.entry("0023", "SC은행"),
            Map.entry("0027", "씨티은행"),
            Map.entry("0031", "대구은행"),
            Map.entry("0032", "부산은행"),
            Map.entry("0034", "광주은행"),
            Map.entry("0035", "제주은행"),
            Map.entry("0037", "전북은행"),
            Map.entry("0039", "경남은행"),
            Map.entry("0045", "MG새마을금고"),
            Map.entry("0048", "신협"),
            Map.entry("0071", "우체국"),
            Map.entry("0081", "하나은행"),
            Map.entry("0088", "신한은행"),
            Map.entry("0089", "케이뱅크")
    );

    @Override
    public Optional<SettlementTransferLink> create(
            String bankCode,
            String accountNumber,
            long amount
    ) {
        String bankName = BANK_NAMES.get(bankCode);
        if (bankName == null || accountNumber == null || amount <= 0
                || !accountNumber.matches("[0-9-]+")) {
            return Optional.empty();
        }
        String normalizedAccountNumber = accountNumber.replace("-", "");
        if (normalizedAccountNumber.isEmpty() || normalizedAccountNumber.length() > 14) {
            return Optional.empty();
        }
        return Optional.of(new SettlementTransferLink(
                PROVIDER,
                "supertoss://send?bank=" + encode(bankName)
                        + "&accountNo=" + normalizedAccountNumber
                        + "&amount=" + amount
        ));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
