package com.amateur.howmuchbackend.application.account.policy;

import com.amateur.howmuchbackend.application.port.in.account.result.AccountTransactionResult;
import com.amateur.howmuchbackend.domain.account.AccountTransactionType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("계좌 거래 표시 정규화 Policy")
class AccountTransactionDisplayNormalizerTest {
    private final AccountTransactionDisplayNormalizer normalizer = new AccountTransactionDisplayNormalizer();

    @Test
    @DisplayName("농협 카드 원문에서 거래처명과 카드 유형을 추출한다")
    void normalizesNhCardDescription() {
        var display = normalizer.normalize(
                "0011", "NH체크 쿠팡(쿠페이) 농협 723110", AccountTransactionType.EXPENSE
        );

        assertThat(display.title()).isEqualTo("쿠팡(쿠페이)");
        assertThat(display.kind()).isEqualTo(AccountTransactionResult.Display.Kind.CARD);
    }

    @Test
    @DisplayName("입금 설명에서 상대방 이름과 이체 유형을 추출한다")
    void normalizesTransferDescription() {
        var display = normalizer.normalize(
                "0011", "실시간이체 민선영 입금 농협 001191", AccountTransactionType.INCOME
        );

        assertThat(display.title()).isEqualTo("민선영");
        assertThat(display.kind()).isEqualTo(AccountTransactionResult.Display.Kind.TRANSFER);
    }

    @ParameterizedTest
    @CsvSource({
            "0020, ATM 출금 우리은행 1234, ATM",
            "0004, 수수료 국민은행 1234, FEE",
            "0088, 알 수 없는 거래, OTHER"
    })
    @DisplayName("거래 유형을 안전한 공통 규칙으로 판별한다")
    void classifiesCommonKinds(String organization, String description, AccountTransactionResult.Display.Kind kind) {
        assertThat(normalizer.normalize(organization, description, AccountTransactionType.EXPENSE).kind())
                .isEqualTo(kind);
    }

    @Test
    @DisplayName("빈 설명은 null 제목과 OTHER로 fallback한다")
    void fallsBackForBlankDescription() {
        assertThat(normalizer.normalize("0011", "  ", AccountTransactionType.EXPENSE))
                .isEqualTo(new AccountTransactionResult.Display(null, AccountTransactionResult.Display.Kind.OTHER));
    }
}
