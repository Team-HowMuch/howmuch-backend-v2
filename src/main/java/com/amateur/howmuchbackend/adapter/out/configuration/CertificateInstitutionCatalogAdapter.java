package com.amateur.howmuchbackend.adapter.out.configuration;

import com.amateur.howmuchbackend.application.port.in.certificate.result.CertificateInstitutionResult;
import com.amateur.howmuchbackend.application.port.out.certificate.CertificateInstitutionCatalogPort;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateInstitutionCatalogAdapter implements CertificateInstitutionCatalogPort {
    private final LoadCommonCodePort codes;
    private final CertificateProperties properties;

    public CertificateInstitutionCatalogAdapter(LoadCommonCodePort codes, CertificateProperties p) {
        this.codes = codes;
        this.properties = p;
    }

    public List<CertificateInstitutionResult> institutions() {
        return codes.findActiveByGroupCode("BANK").stream()
                .map(
                        bank -> {
                            var rule =
                                    properties.institutions().stream()
                                            .filter(i -> bank.code().equals(i.code()))
                                            .findFirst()
                                            .orElse(null);
                            return new CertificateInstitutionResult(
                                    bank.code(),
                                    bank.name(),
                                    true,
                                    rule != null && rule.registrationAccountRequired(),
                                    rule != null && rule.accountListAccountRequired(),
                                    rule != null && rule.birthDateRequired(),
                                    rule == null ? null : rule.reference());
                        })
                .toList();
    }
}
