package com.amateur.howmuchbackend.application.port.in.settlement;

public interface SendAutomaticSettlementRemindersUseCase {
    int sendDue(int batchSize);
}
