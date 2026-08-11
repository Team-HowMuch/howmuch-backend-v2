package com.amateur.howmuchbackend.adapter.in.web.user.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.request.UpdateMyProfileRequest;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.response.MyProfileWebResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "사용자 API", description = "사용자 관련 API")
public interface UserDocs {
    @Operation(
            summary = "내 정보 조회",
            description = "현재 로그인한 사용자의 정보를 조회합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<MyProfileWebResponse> getMyProfile();

    @Operation(
            summary = "내 아바타 조회",
            description = "현재 로그인한 사용자의 등록된 아바타 이미지를 조회합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            content = @Content(schema = @Schema(type = "string", format = "binary"))
    )
    ResponseEntity<byte[]> getMyProfileImage();

    @Operation(
            summary = "내 프로필 수정",
            description = "닉네임, 아바타 업로드 또는 기본 이미지 복원을 적용합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<MyProfileWebResponse> updateMyProfile(UpdateMyProfileRequest request);
}
