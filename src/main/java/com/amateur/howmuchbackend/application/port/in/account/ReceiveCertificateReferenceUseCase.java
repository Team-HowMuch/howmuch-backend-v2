package com.amateur.howmuchbackend.application.port.in.account;

import java.util.UUID;

public interface ReceiveCertificateReferenceUseCase {
    boolean receiveReference(UUID messageId, UUID operationId, String hash);
}
