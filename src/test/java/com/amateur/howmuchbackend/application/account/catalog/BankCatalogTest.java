package com.amateur.howmuchbackend.application.account.catalog;

import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BankCatalogTest {
    @Test
    void bankCatalog_betaBanks_requireIdAndPassword() {
        LoadCommonCodePort port = mock(LoadCommonCodePort.class);
        when(port.findActiveByGroupCode("BANK")).thenReturn(List.of(
                bank("0003", "기업은행"),
                bank("0023", "SC은행"),
                bank("0031", "대구은행")
        ));
        when(port.existsActiveByGroupCodeAndCode("BANK", "0003")).thenReturn(true);
        BankCatalog catalog = new BankCatalog(port);

        assertThat(catalog.getBanks()).extracting(BankCatalogResult::organization).containsExactly("0003");
        assertThat(catalog.getAllBanks()).extracting(BankCatalogResult::organization)
                .containsExactly("0003", "0023", "0031");
        catalog.validate("0003");
        assertThatThrownBy(() -> catalog.validate("0004"))
                .isInstanceOf(ApplicationException.class)
                .extracting("statusCode").isEqualTo(ExternalConnectionStatusCode.BANK_NOT_SUPPORTED);
        assertThatThrownBy(() -> catalog.validate("0031"))
                .isInstanceOf(ApplicationException.class)
                .extracting("statusCode").isEqualTo(ExternalConnectionStatusCode.BANK_NOT_SUPPORTED);
    }

    private CommonCode bank(String code, String name) {
        return new CommonCode("BANK", code, name, null, 1, null, null, null, true);
    }
}
