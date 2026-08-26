package com.amateur.howmuchbackend.adapter.out.transfer;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Toss 정산 송금 딥링크")
class TossSettlementTransferLinkAdapterTest {
    @Mock private BankCatalog bankCatalog;

    private TossSettlementTransferLinkAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new TossSettlementTransferLinkAdapter(bankCatalog);
    }

    @Test
    @DisplayName("은행명은 URL 인코딩하고 계좌번호의 하이픈은 제거한다")
    void createsTossTransferLink() {
        when(bankCatalog.findTossDeepLinkBankName("0004"))
                .thenReturn(Optional.of("KB국민은행"));

        var link = adapter.create("0004", "100-212-3456789", 6_000);

        assertThat(link).hasValueSatisfying(value -> {
            assertThat(value.provider()).isEqualTo("TOSS");
            assertThat(value.url()).isEqualTo(
                    "supertoss://send?bank=KB%EA%B5%AD%EB%AF%BC%EC%9D%80%ED%96%89"
                            + "&accountNo=1002123456789&amount=6000"
            );
        });
    }

    @Test
    @DisplayName("지원하지 않는 은행과 유효하지 않은 송금 입력은 링크를 만들지 않는다")
    void rejectsUnsupportedOrInvalidInput() {
        when(bankCatalog.findTossDeepLinkBankName("0090")).thenReturn(Optional.empty());

        assertThat(adapter.create("0090", "1002123456789", 6_000)).isEmpty();
        assertThat(adapter.create("0004", "****6789", 6_000)).isEmpty();
        assertThat(adapter.create("0004", "1002123456789", 0)).isEmpty();
    }
}
