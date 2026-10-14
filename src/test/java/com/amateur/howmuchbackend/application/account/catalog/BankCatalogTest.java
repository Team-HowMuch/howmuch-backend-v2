package com.amateur.howmuchbackend.application.account.catalog;

import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BankCatalogTest {
    @ParameterizedTest
    @ValueSource(strings = {"1", "3"})
    void bankCatalog_betaBanks_requireIdAndPassword(String credentialsAttribute) {
        LoadCommonCodePort port = mock(LoadCommonCodePort.class);
        when(port.findActiveByGroupCode("BANK")).thenReturn(List.of(
                bank("0003", "기업은행", "other"),
                bank("0023", "SC은행", "1"),
                bank("0031", "대구은행", credentialsAttribute)
        ));
        when(port.existsActiveByGroupCodeAndCode("BANK", "0003")).thenReturn(true);
        when(port.existsActiveByGroupCodeAndCode("BANK", "0031")).thenReturn(true);
        when(port.findActiveByGroupCodeAndCode("BANK", "0031"))
                .thenReturn(java.util.Optional.of(bank("0031", "대구은행", credentialsAttribute)));
        when(port.findActiveByGroupCodeAndCode("BANK", "0003"))
                .thenReturn(java.util.Optional.of(bank("0003", "기업은행", "other")));
        BankCatalog catalog = new BankCatalog(port);

        assertThat(catalog.getBanks()).extracting(BankCatalogResult::organization).containsExactly("0003", "0031");
        assertThat(catalog.getAllBanks()).extracting(BankCatalogResult::organization)
                .containsExactly("0003", "0023", "0031");
        catalog.validate("0003");
        catalog.validate("0031");
        assertThat(catalog.requiresAccountCredentials("0031")).isTrue();
        assertThat(catalog.requiresAccountCredentials("0003")).isFalse();
        assertThatThrownBy(() -> catalog.validate("0004"))
                .isInstanceOf(ApplicationException.class)
                .extracting("statusCode").isEqualTo(ExternalConnectionStatusCode.BANK_NOT_SUPPORTED);
        assertThat(catalog.getAllBanks()).extracting(BankCatalogResult::requiresAccountCredentials)
                .containsExactly(false, false, true);
    }

    private CommonCode bank(String code, String name) {
        return bank(code, name, null);
    }

    private CommonCode bank(String code, String name, String attr3) {
        return new CommonCode("BANK", code, name, null, 1, null, null, attr3, true);
    }
}
