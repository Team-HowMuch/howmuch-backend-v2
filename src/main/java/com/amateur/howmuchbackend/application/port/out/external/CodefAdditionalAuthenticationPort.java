package com.amateur.howmuchbackend.application.port.out.external;

import com.amateur.howmuchbackend.application.certificate.model.CertificateProviderResult;

public interface CodefAdditionalAuthenticationPort {
    byte[] acceptSms(byte[] context, String code);

    CertificateProviderResult resume(byte[] acceptedContext);

    default byte[] prepareOperation(
            byte[] context,
            com.amateur.howmuchbackend.application.external.model.FinanceOperation operation) {
        throw new UnsupportedOperationException();
    }

    default com.amateur.howmuchbackend.application.external.model.FinanceOperationResult
            resumeOperation(byte[] context) {
        throw new UnsupportedOperationException();
    }

    default byte[] encodeCompletion(
            com.amateur.howmuchbackend.application.external.model.FinanceAuthenticationCompletion
                    result) {
        throw new UnsupportedOperationException();
    }

    default com.amateur.howmuchbackend.application.external.model.FinanceAuthenticationCompletion
            decodeCompletion(byte[] bytes) {
        throw new UnsupportedOperationException();
    }
}
