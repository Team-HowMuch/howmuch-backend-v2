package com.amateur.howmuchbackend.application.port.out.external;

import com.amateur.howmuchbackend.domain.external.BankAuthenticationMethod;

import java.util.List;

/** Current successful bank authentication metadata, scoped to the existing connection. */
public interface InstitutionAuthenticationStorePort {
    List<String> findCertificateOrganizations(long connectionId);

    void recordMethod(long connectionId, String organization, BankAuthenticationMethod method);
}
