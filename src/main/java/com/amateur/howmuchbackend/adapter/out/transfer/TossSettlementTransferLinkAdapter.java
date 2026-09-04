package com.amateur.howmuchbackend.adapter.out.transfer;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementTransferLinkPort;
import lombok.RequiredArgsConstructor;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

@RequiredArgsConstructor
public class TossSettlementTransferLinkAdapter implements SettlementTransferLinkPort {
    private static final String PROVIDER = "TOSS";

    private final BankCatalog bankCatalog;

    @Override
    public Optional<SettlementTransferLink> create(
            String bankCode,
            String accountNumber,
            long amount,
            String receiverName
    ) {
        if (accountNumber == null || amount <= 0
                || !accountNumber.matches("[0-9-]+")) {
            return Optional.empty();
        }
        String normalizedAccountNumber = accountNumber.replace("-", "");
        if (normalizedAccountNumber.isEmpty() || normalizedAccountNumber.length() > 14) {
            return Optional.empty();
        }
        return bankCatalog.findTossDeepLinkBankName(bankCode)
                .map(bankName -> new SettlementTransferLink(
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
