package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CODEF 계좌 거래 Adapter")
class CodefBankTransactionAdapterTest {
    @Mock
    private CodefClient client;

    @Mock
    private CodefObservability observability;

    @ParameterizedTest
    @CsvSource({
            "CF-12801, BANK_CREDENTIAL_INVALID",
            "CF-12834, BANK_CREDENTIAL_LOCKED",
            "CF-03002, CODEF_ADDITIONAL_AUTH_REQUIRED",
            "CF-12001, CODEF_USER_ACTION_TIMEOUT",
            "CF-00023, CODEF_RATE_LIMITED",
            "CF-09999, CODEF_UPSTREAM_UNAVAILABLE",
            "CF-04000, BANK_CONNECTION_FAILED"
    })
    void bankTransactions_officialFailureCodes_mapped(String code, ExternalConnectionStatusCode expected) {
        assertThat(CodefFailureMapper.map(code, ExternalConnectionStatusCode.CODEF_UPSTREAM_UNAVAILABLE, null)
                .getStatusCode()).isEqualTo(expected);
    }

    @Test
    void preservesFallbackAndCauseForUnknownFailureCode() {
        IllegalStateException cause = new IllegalStateException("upstream failed");

        var failure = CodefFailureMapper.map(
                "CF-99998", ExternalConnectionStatusCode.CODEF_UNMAPPED_ERROR, cause);

        assertThat(failure.getStatusCode()).isEqualTo(ExternalConnectionStatusCode.CODEF_UNMAPPED_ERROR);
        assertThat(failure).hasCause(cause);
    }

    @Test
    void classifiesMissingCodefFailureCodeAsInvalidResponse() {
        var failure = CodefFailureMapper.map(
                null, ExternalConnectionStatusCode.CODEF_RESPONSE_INVALID, null);

        assertThat(failure.getStatusCode()).isEqualTo(ExternalConnectionStatusCode.CODEF_RESPONSE_INVALID);
    }

    @ParameterizedTest
    @CsvSource({
            "503, CODEF_UPSTREAM_UNAVAILABLE",
            "400, CODEF_RESPONSE_INVALID"
    })
    void classifiesCodeLessHttpResponsesByTransportStatus(int httpStatus, ExternalConnectionStatusCode expected)
            throws Exception {
        when(client.post(eq("/v1/kr/bank/p/account/transaction-list"), any()))
                .thenThrow(new CodefClient.CodefHttpException(httpStatus, "", null));
        when(client.decode("")).thenReturn(new ObjectMapper().readTree("{}"));

        assertThatThrownBy(() -> new CodefBankTransactionAdapter(client, observability).fetch(
                "CID-1", "0020", "1002123456789",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(expected));
    }

    @ParameterizedTest
    @CsvSource({
            "20260905,091619,2026-09-05T00:16:19",
            "20260905,000000,2026-09-04T15:00:00",
            "20260905,085959,2026-09-04T23:59:59",
            "20260905,090000,2026-09-05T00:00:00",
            "20260901,000000,2026-08-31T15:00:00",
            "20270101,000000,2026-12-31T15:00:00"
    })
    @DisplayName("KST 거래 벽시계를 자정·월말·연말에도 정확한 UTC로 복원한다")
    void convertsKstWallClockToUtc(String date, String time, String expectedUtc) {
        var response = new ObjectMapper().valueToTree(Map.of(
                "result", Map.of("code", "CF-00000"),
                "data", Map.of("resTrHistoryList", java.util.List.of(Map.of(
                        "resAccountTrDate", date, "resAccountTrTime", time,
                        "resAccountIn", "6000", "resAccountDesc1", "민선영 입금"
                )))
        ));
        when(client.post(eq("/v1/kr/bank/p/account/transaction-list"), any())).thenReturn(response);
        LocalDate queryDate = LocalDate.parse(date, java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        var transactions = new CodefBankTransactionAdapter(client, observability)
                .fetch("CID-1", "0020", "1002123456789", queryDate, queryDate);

        assertThat(transactions).singleElement().satisfies(transaction -> {
            assertThat(transaction.type()).isEqualTo(AccountTransactionType.INCOME);
            assertThat(transaction.transactedAt()).isEqualTo(LocalDateTime.parse(expectedUtc));
        });
        verify(client).post(eq("/v1/kr/bank/p/account/transaction-list"),
                org.mockito.ArgumentMatchers.argThat(payload ->
                        payload instanceof Map<?, ?> fields && date.equals(fields.get("startDate"))
                                && date.equals(fields.get("endDate"))));
    }

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
                                "resAfterTranBalance": "0",
                                "resAccountDesc1": "편의점",
                                "resAccountDesc2": "생활비"
                              },
                              {
                                "resAccountTrDate": "20260821",
                                "resAccountTrTime": "101500",
                                "resAccountIn": "50,000",
                                "resAfterTranBalance": "-5,000",
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
        CodefBankTransactionAdapter adapter = new CodefBankTransactionAdapter(client, observability);

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
            assertThat(transaction.afterBalance()).isEqualByComparingTo("0");
            assertThat(transaction.transactedAt()).isEqualTo(LocalDateTime.of(2026, 8, 19, 15, 15));
            assertThat(transaction.description()).isEqualTo("편의점 생활비");
            assertThat(transaction.description1()).isEqualTo("편의점");
            assertThat(transaction.description2()).isEqualTo("생활비");
            assertThat(transaction.description3()).isNull();
            assertThat(transaction.description4()).isNull();
        });
        assertThat(result.get(1)).satisfies(transaction -> {
            assertThat(transaction.type()).isEqualTo(AccountTransactionType.INCOME);
            assertThat(transaction.afterBalance()).isEqualByComparingTo("-5000");
        });
        verify(observability).record(
                org.mockito.ArgumentMatchers.eq("transaction_list"), org.mockito.ArgumentMatchers.eq("0020"),
                org.mockito.ArgumentMatchers.eq("CF-00000"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }
}
