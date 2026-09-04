package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.Optional;

public interface SettlementTransferLinkPort {
    Optional<SettlementTransferLink> create(String bankCode, String accountNumber, long amount, String receiverName);

    record SettlementTransferLink(String provider, String url) {
    }
}
