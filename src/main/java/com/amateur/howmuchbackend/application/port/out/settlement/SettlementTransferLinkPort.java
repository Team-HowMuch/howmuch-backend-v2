package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.Optional;

public interface SettlementTransferLinkPort {
    Optional<SettlementTransferLink> create(String bankCode, String accountNumber, long amount, String receiverName);

    default Optional<SettlementTransferLink> create(
            String bankCode, String accountNumber, long amount, String receiverName, String senderName
    ) {
        return create(bankCode, accountNumber, amount, receiverName);
    }

    record SettlementTransferLink(String provider, String url) {
    }
}
