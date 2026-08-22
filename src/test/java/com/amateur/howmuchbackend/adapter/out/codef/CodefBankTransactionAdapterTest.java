package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CODEF 계좌 거래 Adapter")
class CodefBankTransactionAdapterTest {
    @Mock
    private CodefClient client;

    @Test
    @DisplayName("수시입출 요청 계약과 출금·입금·서울 시간을 표준 거래로 변환한다")
    void fetchesDemandDepositTransactions() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        when(client.post(eq("/v1/kr/bank/p/account/transaction-list"), any())).thenReturn(
                objectMapper.readTree("""
                        {
                          "result": {"code": "CF-00000"},
                          "data": {
                            "resTrHistoryList": [
                              {
                                "resAccountTrDate": "20260820",
                                "resAccountTrTime": "001500",
                                "resAccountOut": "12,500",
                                "resAfterTranBalance": "1,250,000",
                                "resAccountDesc1": "편의점",
                                "resAccountDesc2": "생활비"
                              },
                              {
                                "resAccountTrDate": "20260821",
                                "resAccountTrTime": "101500",
                                "resAccountIn": "50,000",
                                "resAccountDesc1": "입금"
                              },
                              {
                                "resAccountTrDate": "20260822",
                                "resAccountOut": "1000"
                              }
                            ]
                          }
                        }
                        """)
        );
        CodefBankTransactionAdapter adapter = new CodefBankTransactionAdapter(client);

        var result = adapter.fetch(
                "CID-1", "0020", "1002123456789",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(client).post(eq("/v1/kr/bank/p/account/transaction-list"), captor.capture());
        assertThat(captor.getValue())
                .containsEntry("connectedId", "CID-1")
                .containsEntry("organization", "0020")
                .containsEntry("account", "1002123456789")
                .containsEntry("startDate", "20260801")
                .containsEntry("endDate", "20260831")
                .containsEntry("orderBy", "1")
                .containsEntry("inquiryType", "1");
        assertThat(result).hasSize(2);
        assertThat(result.getFirst()).satisfies(transaction -> {
            assertThat(transaction.type()).isEqualTo(AccountTransactionType.EXPENSE);
            assertThat(transaction.amount()).isEqualByComparingTo("12500");
            assertThat(transaction.transactedAt()).isEqualTo(LocalDateTime.of(2026, 8, 19, 15, 15));
            assertThat(transaction.description()).isEqualTo("편의점 생활비");
        });
        assertThat(result.get(1).type()).isEqualTo(AccountTransactionType.INCOME);
    }
}
