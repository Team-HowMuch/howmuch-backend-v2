package com.amateur.howmuchbackend.application.port.in.recap;

public interface ProcessMonthlyRecapsUseCase {
    void recoverAndRegister();
    int processBatch(int limit);
}
