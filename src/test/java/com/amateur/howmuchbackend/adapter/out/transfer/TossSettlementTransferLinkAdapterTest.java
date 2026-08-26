package com.amateur.howmuchbackend.adapter.out.transfer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Toss 정산 송금 딥링크")
class TossSettlementTransferLinkAdapterTest {
    private final TossSettlementTransferLinkAdapter adapter =
            new TossSettlementTransferLinkAdapter();

    @Test
    @DisplayName("은행명은 URL 인코딩하고 계좌번호의 하이픈은 제거한다")
    void createsTossTransferLink() {
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
        assertThat(adapter.create("0090", "1002123456789", 6_000)).isEmpty();
        assertThat(adapter.create("0004", "****6789", 6_000)).isEmpty();
        assertThat(adapter.create("0004", "1002123456789", 0)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0002", "0003", "0004", "0007", "0011", "0020", "0023", "0027", "0031", "0032",
            "0034", "0035", "0037", "0039", "0045", "0048", "0071", "0081", "0088", "0089"
    })
    @DisplayName("현재 계좌 연결을 지원하는 은행의 Toss 링크를 생성한다")
    void supportsConnectedBankCodes(String bankCode) {
        assertThat(adapter.create(bankCode, "1002123456789", 6_000)).isPresent();
    }
}
