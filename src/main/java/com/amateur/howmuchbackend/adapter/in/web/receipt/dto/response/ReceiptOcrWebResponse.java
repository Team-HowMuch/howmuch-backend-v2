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

        @Schema(
                description = "영수증 전체 할인(원). 특정 품목에 귀속되지 않는 할인이고 양수로 내려와 합계에서 뺍니다. "
                        + "품목 할인(items[].discount)과 배달팁 할인(deliveryFee에 이미 반영)은 포함하지 않습니다. "
                        + "읽지 못하면 null",
                nullable = true
        )
        Long discount,

        @Schema(
                description = "배달비(원). 배달팁 할인·무료배달을 뺀 순액이고 품목(items)에는 들어가지 않습니다. "
                        + "0이면 배달비가 없거나 영수증에서 찾지 못한 경우입니다. "
                        + "null이면 분석 서비스가 값을 주지 않은 경우(구버전 등)로, 배달비가 품목에 섞여 있을 수 있습니다.",
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

            @Schema(
                    description = "줄 금액(원). 옵션 제외, 할인 전, 수량 반영. "
                            + "줄 최종 금액은 price + Σ subItems.price − discount(이 품목의 할인)입니다. 읽지 못하면 null",
                    example = "3500"
            )
            Long price,

            @Schema(description = "이 품목 줄에 귀속되는 할인(원). 양수로 내려와 줄 금액에서 뺍니다. 읽지 못하면 null",
                    nullable = true)
            Long discount,

            @Schema(description = "품목 옵션")
            List<Option> subItems
    ) {
        public static Item from(ReceiptAnalysisResult.Item item) {
            return new Item(item.name(), item.quantity(), item.price(), item.discount(),
                    item.subItems().stream().map(Option::from).toList());
        }
    }

    public record Option(
            @Schema(description = "옵션명. 읽지 못하면 null", example = "샷 추가", nullable = true)
            String name,

            @Schema(
                    description = "옵션 줄 금액(원). 영수증에 찍힌 값 그대로이며, 수량을 곱하지 않고 품목 price에 더합니다. "
                            + "읽지 못하면 null",
                    example = "500",
                    nullable = true
            )
            Long price
    ) {
        public static Option from(ReceiptAnalysisResult.Option option) {
            return new Option(option.name(), option.price());
        }
    }
}
