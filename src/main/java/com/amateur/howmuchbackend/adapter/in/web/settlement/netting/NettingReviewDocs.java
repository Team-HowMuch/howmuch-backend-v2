package com.amateur.howmuchbackend.adapter.in.web.settlement.netting;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.request.ResolveNettingReviewWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response.NettingReviewWebResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.*;

@Tag(name = "압축 정산 입금 검토 API", description = "ADMIN 전용. 실제 은행 거래만 처리하며 결정·담당자·시각·근거를 남깁니다.")
@SecurityRequirement(name = "bearerAuth")
public interface NettingReviewDocs {
    @Operation(summary = "압축 정산 검토 내역 조회")
    ApiResponse<List<NettingReviewWebResponse>> list(UUID nettingId);

    @Operation(
            summary = "실제 입금 검토 결정",
            description =
                    "APPLIED_TO_NETTING은 transferId가 필요합니다. UNRELATED는 검토 귀속만 해제합니다. RETURNED는 외부"
                            + " 은행 반환 근거를 기록하며 서버가 환불하지 않습니다. 다른 목표에 이미 귀속된 거래는 재귀속할 수 없습니다.")
    ApiResponse<NettingReviewWebResponse> decide(
            UUID reviewId, ResolveNettingReviewWebRequest request);
}
