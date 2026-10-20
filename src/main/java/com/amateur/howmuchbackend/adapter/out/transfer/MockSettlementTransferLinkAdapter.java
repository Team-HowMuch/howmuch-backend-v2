package com.amateur.howmuchbackend.adapter.out.transfer;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementTransferLinkPort;
import lombok.RequiredArgsConstructor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@RequiredArgsConstructor
public class MockSettlementTransferLinkAdapter implements SettlementTransferLinkPort {
    // 기존 클라이언트의 Toss 선택 경로에서 Mock 화면을 열도록 식별자는 유지한다.
    private static final String PROVIDER = "TOSS";

    private final BankCatalog bankCatalog;
    private final String baseUrl;

    @Override
    public Optional<SettlementTransferLink> create(
            String bankCode,
            String accountNumber,
            long amount,
            String receiverName
    ) {
        return create(bankCode, accountNumber, amount, receiverName, null);
    }

    @Override
    public Optional<SettlementTransferLink> create(
            String bankCode,
            String accountNumber,
            long amount,
            String receiverName,
            String senderName
    ) {
        if (bankCode == null || !bankCode.matches("[0-9]{4}")
                || accountNumber == null || amount <= 0 || !accountNumber.matches("[0-9-]+")) {
            return Optional.empty();
        }
        String normalizedAccountNumber = accountNumber.replace("-", "");
        if (normalizedAccountNumber.isEmpty() || normalizedAccountNumber.length() > 14) {
            return Optional.empty();
        }
        return bankCatalog.findBankName(bankCode)
                .map(bankName -> new SettlementTransferLink(
                        PROVIDER,
                        baseUrl + "?bankCode=" + encode(bankCode)
                                + "&bank=" + encode(bankName)
                                + "&accountNo=" + normalizedAccountNumber
                                + "&amount=" + amount
                                + (receiverName == null || receiverName.isBlank()
                                ? "" : "&receiver=" + encode(receiverName))
                                + (senderName == null || senderName.isBlank()
                                ? "" : "&senderName=" + encode(senderName))
                ));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
