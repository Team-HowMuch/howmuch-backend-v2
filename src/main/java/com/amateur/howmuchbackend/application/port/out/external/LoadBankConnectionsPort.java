package com.amateur.howmuchbackend.application.port.out.external;

import com.amateur.howmuchbackend.application.account.model.BankConnectionSummary;
import java.util.List;

public interface LoadBankConnectionsPort {
    List<BankConnectionSummary> findActiveByUserId(Long userId);
}
