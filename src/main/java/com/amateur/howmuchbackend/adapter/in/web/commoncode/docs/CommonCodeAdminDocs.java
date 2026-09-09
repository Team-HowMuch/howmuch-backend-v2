package com.amateur.howmuchbackend.adapter.in.web.commoncode.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.request.CommonCodeGroupSearchWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.request.CreateCommonCodeGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.request.UpdateCommonCodeGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.response.CommonCodeGroupSummaryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.commoncode.dto.response.CommonCodeGroupWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

@Tag(name = "공통 코드 관리자 API", description = "공통 코드 그룹과 상세 코드 관리 API")
public interface CommonCodeAdminDocs {
    @Operation(summary = "공통 코드 그룹 검색", security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<List<CommonCodeGroupSummaryWebResponse>> searchGroups(CommonCodeGroupSearchWebRequest request);

    @Operation(summary = "공통 코드 그룹 조회", security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<CommonCodeGroupWebResponse> getGroup(String groupCode);

    @Operation(summary = "공통 코드 그룹 생성", security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<CommonCodeGroupWebResponse> createGroup(CreateCommonCodeGroupWebRequest request);

    @Operation(summary = "공통 코드 그룹과 상세 코드 저장",
            description = "상세 추가·수정 시 attr1~3은 각각 최대 255자이며, 초과하면 400으로 거절합니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    ApiResponse<CommonCodeGroupWebResponse> updateGroup(
            String groupCode,
            UpdateCommonCodeGroupWebRequest request
    );
}
