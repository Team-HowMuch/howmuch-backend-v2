package com.amateur.howmuchbackend.adapter.out.codef;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CODEF 계좌 연결 Adapter")
class CodefBankConnectionAdapterTest {
    @Mock
    private CodefClient client;

    @Mock
    private CodefRsaEncryptor rsaEncryptor;

    @Test
    @DisplayName("DB 저장 범위를 넘는 계좌 잔액은 없는 값으로 처리한다")
    void ignoresBalanceOutsideStorageRange() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        when(client.post(eq("/v1/kr/bank/p/account/account-list"), any())).thenReturn(
                objectMapper.readTree("""
                        {
                          "result": {"code": "CF-00000"},
                          "data": {
                            "resDepositTrust": [
                              {
                                "resAccount": "1002123456789",
                                "resAccountName": "정상 잔액 통장",
                                "resAccountBalance": "1,000,000",
                                "resAccountCurrency": "KRW"
                              },
                              {
                                "resAccount": "2002123456789",
                                "resAccountName": "범위 초과 통장",
                                "resAccountBalance": "1000000000000000",
                                "resAccountCurrency": "KRW"
                              }
                            ]
                          }
                        }
                        """)
        );
        CodefBankConnectionAdapter adapter = new CodefBankConnectionAdapter(client, rsaEncryptor);

        var result = adapter.findAccounts("CID-1", "0031", null);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().balance()).isEqualByComparingTo("1000000");
        assertThat(result.get(1).balance()).isNull();
    }
}
