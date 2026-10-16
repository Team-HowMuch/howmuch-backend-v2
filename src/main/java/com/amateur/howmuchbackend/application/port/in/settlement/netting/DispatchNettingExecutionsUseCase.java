package com.amateur.howmuchbackend.application.port.in.settlement.netting;

public interface DispatchNettingExecutionsUseCase {
    void dispatch();

    void heartbeat();
}
