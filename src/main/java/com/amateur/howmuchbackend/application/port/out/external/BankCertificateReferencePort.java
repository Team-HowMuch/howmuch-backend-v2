package com.amateur.howmuchbackend.application.port.out.external;

import com.amateur.howmuchbackend.application.account.model.CertificateReferenceSecret;
import com.amateur.howmuchbackend.domain.external.BankAuthenticationMethod;

import java.util.List;

public interface BankCertificateReferencePort {
    List<RegisteredAccount> registeredAccounts(String connectedId);

    void addReference(
            String connectedId,
            String sourceOrganization,
            String organization,
            CertificateReferenceSecret secret);

    record RegisteredAccount(String organization, BankAuthenticationMethod authenticationMethod) {
        public boolean certificate() {
            return authenticationMethod == BankAuthenticationMethod.CERTIFICATE;
        }
    }
}
