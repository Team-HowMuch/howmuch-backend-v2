package com.amateur.howmuchbackend.adapter.out.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.infra.certificate.config.CertificateProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

@ExtendWith(MockitoExtension.class)
class CertificateInstitutionCatalogAdapterTest {
    @Mock LoadCommonCodePort codes;

    @Test
    void activeBanksAreSupportedWithoutInstitutionConfiguration() {
        when(codes.findActiveByGroupCode("BANK"))
                .thenReturn(List.of(bank("0020"), bank("0031")));
        var catalog = new CertificateInstitutionCatalogAdapter(codes, properties(List.of()));

        var result = catalog.institutions();

        assertThat(result).extracting(i -> i.organization()).containsExactly("0020", "0031");
        assertThat(result)
                .allSatisfy(
                        i -> {
                            assertThat(i.supported()).isTrue();
                            assertThat(i.registrationAccountRequired()).isFalse();
                            assertThat(i.accountListAccountRequired()).isFalse();
                            assertThat(i.birthDateRequired()).isFalse();
                        });
    }

    @Test
    void inputRequirementsAreIndependentOfReviewedMetadataAndOtherBanks() {
        when(codes.findActiveByGroupCode("BANK"))
                .thenReturn(List.of(bank("0020"), bank("0031")));
        var catalog =
                new CertificateInstitutionCatalogAdapter(
                        codes,
                        properties(
                                List.of(
                                        new CertificateProperties.Institution(
                                                "0020", false, true, false, true, null),
                                        new CertificateProperties.Institution(
                                                "0031", true, false, true, false, null))));

        var result = catalog.institutions();

        assertThat(result).allSatisfy(i -> assertThat(i.supported()).isTrue());
        assertThat(result.get(0).registrationAccountRequired()).isTrue();
        assertThat(result.get(0).accountListAccountRequired()).isFalse();
        assertThat(result.get(0).birthDateRequired()).isTrue();
        assertThat(result.get(1).registrationAccountRequired()).isFalse();
        assertThat(result.get(1).accountListAccountRequired()).isTrue();
        assertThat(result.get(1).birthDateRequired()).isFalse();
    }

    private CommonCode bank(String code) {
        // ID/PW requirements must not become certificate input requirements.
        return new CommonCode("BANK", code, code, null, 1, null, null, "true", true);
    }

    private CertificateProperties properties(List<CertificateProperties.Institution> institutions) {
        return new CertificateProperties(
                true, "", "", "", institutions, null, null, null, null, null, null);
    }
}
