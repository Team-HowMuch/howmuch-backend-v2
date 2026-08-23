package com.amateur.howmuchbackend.adapter.out.receipt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("로컬 OCR 영수증 Stub")
class LocalReceiptExtractionStubAdapterTest {
    private final LocalReceiptExtractionStubAdapter adapter =
            new LocalReceiptExtractionStubAdapter();

    @Test
    @DisplayName("유효한 참조에 합계가 일치하는 결정적 영수증 결과를 반환한다")
    void returnsDeterministicReceiptExtraction() {
        var extraction = adapter.extract(1L, "receipt-demo").orElseThrow();

        assertThat(extraction.description()).isEqualTo("OCR 영수증");
        assertThat(extraction.totalAmount()).isEqualTo(30_000);
        assertThat(extraction.items()).extracting(item -> item.amount())
                .containsExactly(10_000L, 20_000L);
        assertThat(extraction.items().stream().mapToLong(item -> item.amount()).sum())
                .isEqualTo(extraction.totalAmount());
    }

    @Test
    @DisplayName("빈 참조는 OCR 결과 없음으로 처리한다")
    void returnsEmptyForBlankReference() {
        assertThat(adapter.extract(1L, "  ")).isEmpty();
        assertThat(adapter.extract(null, "receipt-demo")).isEmpty();
    }
}
