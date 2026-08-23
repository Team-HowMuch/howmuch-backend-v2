package com.amateur.howmuchbackend.adapter.out.receipt;

import com.amateur.howmuchbackend.application.port.out.settlement.ExtractReceiptItemsPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("prod")
public class UnavailableReceiptExtractionAdapter implements ExtractReceiptItemsPort {
    @Override
    public Optional<ReceiptExtraction> extract(Long requesterId, String receiptReference) {
        throw ApplicationException.of(
                SettlementStatusCode.SETTLEMENT_RECEIPT_EXTRACTION_UNAVAILABLE
        );
    }
}
