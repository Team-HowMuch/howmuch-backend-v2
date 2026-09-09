package com.amateur.howmuchbackend.application.group.service;

import com.amateur.howmuchbackend.application.port.out.group.GroupImageStoragePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class GroupImageTransactionSupport {
    private final GroupImageStoragePort groupImageStoragePort;

    public void deleteOnRollback(String newlyStoredFileName) {
        if (newlyStoredFileName == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteSafely(newlyStoredFileName, "rollback");
                }
            }
        });
    }

    public void replaceAfterCompletion(
            String previousFileName,
            String currentFileName,
            String newlyStoredFileName
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deletePreviousIfChanged(previousFileName, currentFileName);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deletePreviousIfChanged(previousFileName, currentFileName);
            }

            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK && newlyStoredFileName != null) {
                    deleteSafely(newlyStoredFileName, "rollback");
                }
            }
        });
    }

    private void deletePreviousIfChanged(String previousFileName, String currentFileName) {
        if (previousFileName != null && !previousFileName.equals(currentFileName)) {
            deleteSafely(previousFileName, "after_commit");
        }
    }

    private void deleteSafely(String fileName, String phase) {
        try {
            groupImageStoragePort.delete(fileName);
        } catch (RuntimeException exception) {
            log.atWarn()
                    .addKeyValue("event", "image_cleanup_failed")
                    .addKeyValue("imageDomain", "group")
                    .addKeyValue("fileName", fileName)
                    .addKeyValue("phase", phase)
                    .addKeyValue("exceptionType", exception.getClass().getName())
                    .log("Image cleanup failed; manual cleanup requires checking current references");
        }
    }
}
