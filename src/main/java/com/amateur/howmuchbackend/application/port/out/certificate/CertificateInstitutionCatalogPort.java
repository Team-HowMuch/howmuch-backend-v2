package com.amateur.howmuchbackend.application.port.out.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateInstitutionResult;

import java.util.List;

public interface CertificateInstitutionCatalogPort {
    List<CertificateInstitutionResult> institutions();
}
