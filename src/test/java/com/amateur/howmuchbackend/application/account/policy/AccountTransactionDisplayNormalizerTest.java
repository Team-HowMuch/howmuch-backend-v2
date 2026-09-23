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
    void nhCardDescription_merchantAndCardType_extracted() {
        var display = normalizer.normalize(
                "0011", "NH체크 쿠팡(쿠페이) 농협 723110", AccountTransactionType.EXPENSE
        );

        assertThat(display.title()).isEqualTo("쿠팡(쿠페이)");
        assertThat(display.kind()).isEqualTo(AccountTransactionResult.Display.Kind.CARD);
    }

    @Test
    @DisplayName("인식된 NH 체크 방식 토큰만 있어도 일반 가맹점을 카드로 분류한다")
    void nhCheckMethod_recognizedToken_classifiedAsCard() {
        var display = normalizer.normalize(
                "0011", "NH체크 이마트 농협 723110", AccountTransactionType.EXPENSE
        );

        assertThat(display.title()).isEqualTo("이마트");
        assertThat(display.kind()).isEqualTo(AccountTransactionResult.Display.Kind.CARD);
    }

    @Test
    @DisplayName("입금 설명에서 상대방 이름과 이체 유형을 추출한다")
    void transferDescription_counterpartyAndType_extracted() {
        var display = normalizer.normalize(
                "0011", "실시간이체 민선영 입금 농협 001191", AccountTransactionType.INCOME
        );

        assertThat(display.title()).isEqualTo("민선영");
        assertThat(display.kind()).isEqualTo(AccountTransactionResult.Display.Kind.TRANSFER);
    }

    @Test
    @DisplayName("농협은 분리된 거래기록사항 필드를 제목으로 우선 사용한다")
    void nhDescriptionField_available_usedAsTitle() {
        var display = normalizer.normalize(
                "0011",
                "실시간이체 [상대방명](카카오 농협 [계좌식별자]",
                AccountTransactionType.EXPENSE,
                "거래기록사항",
                "실시간이체",
                "[상대방명](카카오",
                "농협 [계좌식별자]"
        );

        assertThat(display.title()).isEqualTo("[상대방명](카카오");
        assertThat(display.kind()).isEqualTo(AccountTransactionResult.Display.Kind.TRANSFER);
    }

    @Test
    @DisplayName("대구은행은 분리된 비고 필드를 제목으로 우선 사용한다")
    void prefersDgbMemoField() {
        var display = normalizer.normalize(
                "0031",
                "이체 [가맹점명] 대구은행",
                AccountTransactionType.EXPENSE,
                null,
                "이체",
                "[가맹점명]",
                "대구은행"
        );

        assertThat(display.title()).isEqualTo("[가맹점명]");
        assertThat(display.kind()).isEqualTo(AccountTransactionResult.Display.Kind.TRANSFER);
    }

    @Test
    @DisplayName("검증되지 않은 은행은 합쳐진 설명에서 제목을 추출한다")
    void fallsBackToJoinedDescriptionForOtherBanks() {
        var display = normalizer.normalize(
                "0020",
                "편의점 생활비 우리은행 1234",
                AccountTransactionType.EXPENSE,
                "편의점",
                "생활비",
                null,
                null
        );

        assertThat(display.title()).isEqualTo("편의점 생활비");
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
