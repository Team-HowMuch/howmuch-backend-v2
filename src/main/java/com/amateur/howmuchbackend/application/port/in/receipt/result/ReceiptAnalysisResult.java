package com.amateur.howmuchbackend.application.port.in.receipt.result;

import java.util.List;

/**
 * 영수증 분석 결과.
 *
 * <p>OCR 서비스 응답의 디버깅용 필드(원본 모델 응답, 인식 라인, 보정 내역)는 담지 않는다.
 * 내부 모델 동작이 공개 API가 되면 모델을 바꿀 때마다 앱 계약이 흔들린다.
 *
 * <p>대부분의 값이 비어 있을 수 있다. 영수증이 흐리거나 잘려도 읽은 만큼은 돌려준다.
 */
public record ReceiptAnalysisResult(
        String storeName,
        String purchasedAt,
        List<Item> items,
        Long totalAmount,
        String paymentMethod,
        boolean totalVerified,
        Long discount
) {
    public ReceiptAnalysisResult(String storeName, String purchasedAt, List<Item> items,
                                 Long totalAmount, String paymentMethod, boolean totalVerified) {
        this(storeName, purchasedAt, items, totalAmount, paymentMethod, totalVerified, null);
    }

    public ReceiptAnalysisResult {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Item(
            String name,
            Integer quantity,
            Long price,
            Long discount,
            List<Option> subItems
    ) {
        public Item(String name, Integer quantity, Long price) {
            this(name, quantity, price, null, List.of());
        }

        public Item {
            subItems = subItems == null ? List.of() : List.copyOf(subItems);
        }
    }

    public record Option(String name, Long price) {
    }
}
