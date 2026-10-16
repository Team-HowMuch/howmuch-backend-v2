package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.result.CertificateReferenceOperationResult;

import java.util.UUID;

public interface GetCertificateReferenceOperationUseCase {
    CertificateReferenceOperationResult getCertificateReferenceOperation(UUID operationId);
}
