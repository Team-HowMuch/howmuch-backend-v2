package com.amateur.howmuchbackend.application.port.out.account;

public interface AccountTransactionSyncCommandPublisherPort {
    void publish(AccountTransactionSyncCommandOutboxPort.PendingCommand command);
}
