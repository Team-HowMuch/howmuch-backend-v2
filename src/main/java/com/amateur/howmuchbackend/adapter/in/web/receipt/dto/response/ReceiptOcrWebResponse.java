package com.amateur.howmuchbackend.adapter.in.web.receipt.dto.response;

import com.amateur.howmuchbackend.application.port.in.receipt.result.ReceiptAnalysisResult;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record ReceiptOcrWebResponse(
        @Schema(description = "상호명. 읽지 못하면 null", example = "스타필드")
        String storeName,

        @Schema(description = "구매 일시. 시각이 없으면 날짜만. 읽지 못하면 null", example = "2026-09-16 16:47")
        String purchasedAt,

        @Schema(description = "구매 품목")
        List<Item> items,

        @Schema(description = "합계 금액(원). 읽지 못하면 null", example = "60000")
        Long totalAmount,

        @Schema(description = "결제 수단. 읽지 못하면 null", example = "카드")
        String paymentMethod,

        @Schema(
                description = "합계가 영수증의 다른 숫자와 교차 검증되었는지. "
                        + "false면 사용자에게 합계를 확인받는 것을 권장합니다.",
                example = "true"
        )
        boolean totalVerified,

        @Schema(description = "영수증 전체 할인(원). 읽지 못하면 null", nullable = true)
        Long discount,

        @Schema(
                description = "배달비(원). 배달팁 할인·무료배달을 뺀 순액이고 품목(items)에는 들어가지 않습니다. "
                        + "배달비가 없으면 0, 분석 서비스가 값을 주지 않으면 null",
                example = "3000",
                nullable = true
        )
        Long deliveryFee
) {
    public static ReceiptOcrWebResponse from(ReceiptAnalysisResult result) {
        return new ReceiptOcrWebResponse(
                result.storeName(),
                result.purchasedAt(),
                result.items().stream().map(Item::from).toList(),
                result.totalAmount(),
                result.paymentMethod(),
                result.totalVerified(),
                result.discount(),
                result.deliveryFee()
        );
    }

    public record Item(
            @Schema(description = "품목명. 읽지 못하면 null", example = "참치김밥")
            String name,

            @Schema(description = "수량. 읽지 못하면 null", example = "1")
            Integer quantity,

            @Schema(description = "금액(원). 읽지 못하면 null", example = "3500")
            Long price,

            @Schema(description = "품목 할인(원). 읽지 못하면 null", nullable = true)
            Long discount,

            @Schema(description = "품목 옵션")
            List<Option> subItems
    ) {
        public static Item from(ReceiptAnalysisResult.Item item) {
            return new Item(item.name(), item.quantity(), item.price(), item.discount(),
                    item.subItems().stream().map(Option::from).toList());
        }
    }

    public record Option(String name, Long price) {
        public static Option from(ReceiptAnalysisResult.Option option) {
            return new Option(option.name(), option.price());
        }
    }
}
