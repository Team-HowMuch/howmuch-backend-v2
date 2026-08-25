package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.port.out.group.GroupImageStoragePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class GroupImageTransactionSupport {
    private final GroupImageStoragePort groupImageStoragePort;

    public void deleteOnRollback(String newlyStoredFileName) {
        if (newlyStoredFileName == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    groupImageStoragePort.delete(newlyStoredFileName);
                }
            }
        });
    }
}
