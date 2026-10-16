package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.command.StartCertificateReferenceConnectionCommand;
import com.amateur.howmuchbackend.application.port.in.account.result.CertificateReferenceOperationResult;

public interface StartCertificateReferenceConnectionUseCase {
    CertificateReferenceOperationResult startCertificateReference(
            StartCertificateReferenceConnectionCommand command);
}
