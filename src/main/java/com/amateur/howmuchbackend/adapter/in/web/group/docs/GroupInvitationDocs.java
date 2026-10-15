package com.amateur.howmuchbackend.adapter.in.web.group.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.CreateGroupInvitationWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupInvitationWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

@Tag(name = "그룹 초대 API", description = "특정 회원 초대. 7일 유효하며 수락할 때 멤버십을 생성합니다.")
@SecurityRequirement(name = "bearerAuth")
public interface GroupInvitationDocs {
    @Operation(summary = "특정 회원 초대", description = "OWNER만 생성합니다. 유효한 대기 초대가 있으면 기존 초대를 반환합니다. 기존 회원 직접 추가 API와 별도입니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "초대 생성 또는 기존 대기 초대 반환", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_PUBLIC_ID 또는 INVALID_GROUP_INVITATION"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "GROUP_OWNER_REQUIRED 또는 GROUP_ACCESS_DENIED"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "GROUP_NOT_FOUND 또는 GROUP_MEMBER_CANDIDATE_NOT_FOUND"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "GROUP_MEMBER_ALREADY_ACTIVE")
    })
    ApiResponse<GroupInvitationWebResponse> create(@Positive Long groupId, @Valid CreateGroupInvitationWebRequest request);
    @Operation(summary = "내가 받은 초대 조회", description = "수신자만 조회하며 만료된 초대는 EXPIRED를 반환합니다. 다른 사용자에게는 404입니다.")
    ApiResponse<GroupInvitationWebResponse> get(UUID invitationId);
    @Operation(summary = "그룹 초대 수락", description = "수신자만 수락합니다. 멤버십과 ACCEPTED를 함께 저장합니다. 반복 수락은 재가입하지 않습니다. 만료는 410, 거절된 초대는 409입니다.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "초대 수락", useReturnTypeSchema = true),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "GROUP_INVITATION_NOT_FOUND 또는 GROUP_NOT_FOUND"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "GROUP_INVITATION_NOT_PENDING - 취소, 거절 또는 과거 권한"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "410", description = "GROUP_INVITATION_EXPIRED")
    })
    ApiResponse<GroupInvitationWebResponse> accept(UUID invitationId);
    @Operation(summary = "그룹 초대 거절", description = "수신자만 거절합니다. 반복 거절은 동일 결과를 반환합니다. 만료는 410, 수락된 초대는 409입니다.")
    ApiResponse<GroupInvitationWebResponse> decline(UUID invitationId);
}
