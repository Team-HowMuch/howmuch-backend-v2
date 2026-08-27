package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.Optional;

public interface SettlementTransferLinkPort {
    Optional<SettlementTransferLink> create(String bankCode, String accountNumber, long amount);

    record SettlementTransferLink(String provider, String url) {
    }
}
