package com.amateur.howmuchbackend.adapter.out.transfer;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Mock 정산 송금 링크")
class MockSettlementTransferLinkAdapterTest {
    private static final String BASE_URL = "https://howmuch-mock-send.vercel.app/send";
    @Mock private BankCatalog bankCatalog;
    private MockSettlementTransferLinkAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MockSettlementTransferLinkAdapter(bankCatalog, BASE_URL);
    }

    @Test
    @DisplayName("표시용 은행명과 선행 0을 보존한 기관코드·계좌·금액·예금주를 전달한다")
    void mockTransferLink_withoutTossName_preservesBankAndPayee() {
        when(bankCatalog.findBankName("0020")).thenReturn(Optional.of("우리은행"));

        var link = adapter.create("0020", "100-2239945843", 2_000, "현승민").orElseThrow();

        assertThat(link.provider()).isEqualTo("TOSS");
        assertThat(link.url()).startsWith(BASE_URL + "?bankCode=0020&bank=");
        assertThat(query(link.url())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "bankCode", "0020", "bank", "우리은행", "accountNo", "1002239945843",
                "amount", "2000", "receiver", "현승민"
        ));
        verifyNoMoreInteractions(bankCatalog);
    }

    @Test
    @DisplayName("한글·공백·앰퍼샌드·플러스가 쿼리 파라미터를 추가하거나 변형하지 않는다")
    void mockTransferLink_queryValues_encodedWithoutMutation() {
        when(bankCatalog.findBankName("0020")).thenReturn(Optional.of("은행 & +"));
        String url = adapter.create("0020", "000123", 1, "현 승민&sender=다른사람+가").orElseThrow().url();

        assertThat(query(url)).hasSize(5)
                .containsEntry("bank", "은행 & +")
                .containsEntry("receiver", "현 승민&sender=다른사람+가")
                .containsEntry("accountNo", "000123")
                .doesNotContainKeys("sender", "redirect");
        assertThat(url).contains("%26", "%2B");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("예금주가 없으면 receiver를 생략한다")
    void mockTransferLink_missingPayee_omitsReceiver(String receiver) {
        when(bankCatalog.findBankName("0020")).thenReturn(Optional.of("우리은행"));
        assertThat(query(adapter.create("0020", "123", 1, receiver).orElseThrow().url()))
                .hasSize(4).doesNotContainKey("receiver");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"---", "****1234", "123 456", "123456789012345", "１２３"})
    @DisplayName("유효하지 않은 계좌번호는 링크를 만들지 않는다")
    void mockTransferLink_invalidAccount_rejected(String account) {
        assertThat(adapter.create("0020", account, 1, "예금주")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"20", "00020", "abcd", "00 2"})
    @DisplayName("4자리 숫자 기관코드가 없으면 링크를 만들지 않는다")
    void mockTransferLink_invalidBankCode_rejected(String bankCode) {
        assertThat(adapter.create(bankCode, "123", 1, "예금주")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void mockTransferLink_nonPositiveAmount_rejected(long amount) {
        assertThat(adapter.create("0020", "123", amount, "예금주")).isEmpty();
    }

    @Test
    void mockTransferLink_unsupportedBank_rejected() {
        when(bankCatalog.findBankName("9999")).thenReturn(Optional.empty());
        assertThat(adapter.create("9999", "123", 1, "예금주")).isEmpty();
    }

    @Test
    void mockTransferLink_maxAccountLength_usesConfiguredUrl() {
        adapter = new MockSettlementTransferLinkAdapter(bankCatalog, "http://localhost:3000/send");
        when(bankCatalog.findBankName("0020")).thenReturn(Optional.of("우리은행"));
        String url = adapter.create("0020", "12345678901234", Long.MAX_VALUE, "예금주")
                .orElseThrow().url();
        assertThat(url).startsWith("http://localhost:3000/send?");
        assertThat(query(url)).containsEntry("amount", Long.toString(Long.MAX_VALUE));
    }

    private Map<String, String> query(String url) {
        return Arrays.stream(URI.create(url).getRawQuery().split("&"))
                .map(part -> part.split("=", 2))
                .collect(Collectors.toMap(parts -> parts[0],
                        parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
    }
}
