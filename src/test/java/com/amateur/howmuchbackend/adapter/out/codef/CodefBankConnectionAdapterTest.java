package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.port.out.external.BankConnectionPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

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
    @DisplayName("계좌 자격정보는 CODEF 로그인과 계좌조회에 전달하고 비밀번호는 RSA 암호화한다")
    void credentialsIncludedInLoginAndAccountList() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var credentials = new BankAccountCredentials("123-456", "1234");
        when(rsaEncryptor.encrypt("password")).thenReturn("encrypted-login-password");
        when(rsaEncryptor.encrypt("1234")).thenReturn("encrypted-account-password");
        when(client.post(eq("/v1/account/create"), any())).thenReturn(mapper.readTree("""
                {"result":{"code":"CF-00000"},"data":{"connectedId":"CID-1","successList":[{"organization":"0020"}]}}
                """));
        when(client.post(eq("/v1/kr/bank/p/account/account-list"), any())).thenReturn(mapper.readTree("""
                {"result":{"code":"CF-00000"},"data":{"resDepositTrust":[]}}
                """));
        var adapter = new CodefBankConnectionAdapter(client, rsaEncryptor, observability);

        adapter.create(new BankConnectionPort.BankRegistration("0020", "login", "password", null, credentials));
        adapter.findAccounts("CID-1", "0020", null, credentials);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(client).post(eq("/v1/account/create"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> registration = (Map<String, Object>) ((List<?>) ((Map<?, ?>) payload.getValue())
                .get("accountList")).getFirst();
        assertThat(registration).containsEntry("withdrawAccountNo", "123-456")
                .containsEntry("withdrawAccountPassword", "encrypted-account-password");
        verify(client).post(eq("/v1/kr/bank/p/account/account-list"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> accountList = (Map<String, Object>) payload.getValue();
        assertThat(accountList).containsEntry("withdrawAccountNo", "123-456")
                .containsEntry("withdrawAccountPassword", "encrypted-account-password");
    }

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
                new BankConnectionPort.BankRegistration("0020", "login", "password", null, null)))
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
                new BankConnectionPort.BankRegistration("0020", "login", "password", null, null)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(ExternalConnectionStatusCode.CODEF_RESPONSE_INVALID));
    }

    @Test
    @DisplayName("DB 저장 범위를 넘는 계좌 잔액은 없는 값으로 처리한다")
    void bankBalance_outsideStorageRange_treatedAsAbsent() throws Exception {
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

        var result = adapter.findAccounts("CID-1", "0031", null, null);

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
