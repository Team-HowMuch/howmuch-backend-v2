package com.amateur.howmuchbackend.adapter.in.web.user.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.request.SearchAdminUserWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.response.AdminUserSearchWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "사용자 관리자 API", description = "이메일로 접수된 계정 삭제 요청 처리")
public interface AdminUserDeletionDocs {
    @Operation(summary = "삭제 대상 사용자 검색", description = "ADMIN만 사용할 수 있습니다. 휴대폰 번호는 본문에서 받아 정규화 후 정확히 일치하는 활성 일반 사용자만 조회합니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "검색 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_PHONE_NUMBER - 전화번호 형식 오류"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN - 관리자 권한 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_NOT_FOUND - 활성 일반 사용자 없음")
    })
    ApiResponse<AdminUserSearchWebResponse> searchUser(SearchAdminUserWebRequest request);

    @Operation(summary = "관리자 대리 회원 탈퇴", description = "ADMIN이 대상 일반 사용자를 PIN 없이 탈퇴 처리합니다. 자가 탈퇴와 동일한 그룹 OWNER·미완료 정산 차단 규칙을 적용하고 감사 이력을 남깁니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "탈퇴 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN - 관리자 권한 필요 또는 ADMIN 대상"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_NOT_FOUND - 없거나 비활성 대상"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "USER_DELETION_GROUP_OWNER_TRANSFER_REQUIRED 또는 USER_DELETION_BLOCKED_BY_SETTLEMENT")
    })
    ApiResponse<Void> deleteUser(Long userId);
}
