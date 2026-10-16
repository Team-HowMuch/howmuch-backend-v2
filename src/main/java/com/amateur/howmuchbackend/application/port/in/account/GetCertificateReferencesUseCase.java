package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.result.CertificateReferencesResult;

public interface GetCertificateReferencesUseCase {
    CertificateReferencesResult getCertificateReferences(String organization);
}
