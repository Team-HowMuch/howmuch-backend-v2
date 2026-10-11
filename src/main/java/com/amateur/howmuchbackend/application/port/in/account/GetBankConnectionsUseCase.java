package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionSummaryResult;
import java.util.List;

public interface GetBankConnectionsUseCase {
    List<BankConnectionSummaryResult> getConnections();
}
