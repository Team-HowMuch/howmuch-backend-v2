package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Mock
    private CodefObservability observability;

    @Test
    @DisplayName("공식 매핑에 없는 CODEF 오류는 미분류 외부 오류로 구분한다")
    void codefFailure_unknownCode_classifiedAsExternal() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        when(rsaEncryptor.encrypt("password")).thenReturn("encrypted-password");
        when(client.post(eq("/v1/account/create"), any())).thenReturn(objectMapper.readTree("""
                {"result": {"code": "CF-99998", "message": "알 수 없는 실패"}, "data": {"errorList": []}}
                """));
        CodefBankConnectionAdapter adapter = new CodefBankConnectionAdapter(client, rsaEncryptor, observability);

        assertThatThrownBy(() -> adapter.create(
                new BankConnectionPort.BankRegistration("0020", "login", "password", null)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(ExternalConnectionStatusCode.CODEF_UNMAPPED_ERROR));
    }

    @Test
    @DisplayName("성공 응답에 연결 식별자가 없으면 응답 계약 오류로 구분한다")
    void codefSuccess_missingConnectionId_classifiedAsContractError() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        when(rsaEncryptor.encrypt("password")).thenReturn("encrypted-password");
        when(client.post(eq("/v1/account/create"), any())).thenReturn(objectMapper.readTree("""
                {"result": {"code": "CF-00000"}, "data": {"successList": []}}
                """));
        CodefBankConnectionAdapter adapter = new CodefBankConnectionAdapter(client, rsaEncryptor, observability);

        assertThatThrownBy(() -> adapter.create(
                new BankConnectionPort.BankRegistration("0020", "login", "password", null)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(ExternalConnectionStatusCode.CODEF_RESPONSE_INVALID));
    }

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
        CodefBankConnectionAdapter adapter = new CodefBankConnectionAdapter(client, rsaEncryptor, observability);

        var result = adapter.findAccounts("CID-1", "0031", null);

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().balance()).isEqualByComparingTo("1000000");
        assertThat(result.get(1).balance()).isNull();
        org.mockito.Mockito.verify(observability).record(
                org.mockito.ArgumentMatchers.eq("account_list"), org.mockito.ArgumentMatchers.eq("0031"),
                org.mockito.ArgumentMatchers.eq("CF-00000"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.anyLong()
        );
    }
}
