package com.amateur.howmuchbackend.adapter.in.web.settlement.netting;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "그룹 압축 정산 API")
@SecurityRequirement(name = "bearerAuth")
public interface NettingTargetDocs {
    @Operation(summary = "알림에서 압축 정산 열기", description = "그룹 ID를 모르는 알림 진입에서도 현재 멤버 권한을 검사합니다.")
    ApiResponse<GroupNettingWebResponse> get(UUID nettingId);

    @Operation(summary = "원본을 대체한 압축 정산 조회", description = "공개 링크는 지원하지 않으며 현재 그룹 멤버 권한을 검사합니다.")
    ApiResponse<GroupNettingWebResponse> source(UUID settlementId);
}
