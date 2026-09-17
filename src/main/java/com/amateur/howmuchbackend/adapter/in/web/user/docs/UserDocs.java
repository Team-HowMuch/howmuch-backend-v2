package com.amateur.howmuchbackend.adapter.in.web.user.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.request.RegisterPushTargetWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.request.DeleteMyAccountWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.request.UpdateMyProfileRequest;
import com.amateur.howmuchbackend.adapter.in.web.user.dto.response.MyProfileWebResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

@Tag(name = "사용자 API", description = "사용자 관련 API")
public interface UserDocs {
    @Operation(
            summary = "내 정보 조회",
            description = "현재 로그인한 사용자의 정보를 조회합니다. "
                    + "avatarUrl은 이미지 파일 버전 v 쿼리를 포함하며 사진이 없으면 null입니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<MyProfileWebResponse> getMyProfile();

    @Operation(
            summary = "내 아바타 조회",
            description = "현재 로그인한 사용자의 등록된 아바타 이미지를 조회합니다. "
                    + "응답은 private 캐시에서 1일간 재사용할 수 있습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            content = @Content(schema = @Schema(type = "string", format = "binary"))
    )
    ResponseEntity<byte[]> getMyProfileImage();

    @Operation(
            summary = "내 프로필 수정",
            description = "공개 ID, 아바타 업로드 또는 기본 이미지 복원을 적용합니다. "
                    + "공개 ID는 소문자로 정규화하며 영문, 숫자, 점, 밑줄 4~20자만 허용합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "내 프로필 수정 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_PUBLIC_ID - 공개 ID 형식 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "PUBLIC_ID_ALREADY_EXISTS - 공개 ID 중복"
            )
    })
    ApiResponse<MyProfileWebResponse> updateMyProfile(UpdateMyProfileRequest request);

    @Operation(
            summary = "내 앱 푸시 대상 등록",
            description = "Android FCM 또는 iOS APNs 네이티브 푸시 토큰을 "
                    + "현재 앱의 단일 푸시 대상으로 등록합니다. 로그인 또는 세션 복원 직후와 "
                    + "토큰 변경 시 호출합니다. JSON 필드명 firebaseInstallationId는 기존 앱 호환을 위해 유지합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "앱 푸시 대상 등록 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "앱 푸시 토큰 형식 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "PUSH_TARGET_REGISTRATION_CONFLICT - 동시 등록 충돌"
            )
    })
    ApiResponse<Void> registerPushTarget(RegisterPushTargetWebRequest request);

    @Operation(
            summary = "내 앱 푸시 대상 해제",
            description = "현재 로그인한 사용자의 앱 푸시 대상을 해제합니다. "
                    + "로그아웃 시 인증 토큰을 삭제하기 전에 호출합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "앱 푸시 대상 해제 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            )
    })
    ApiResponse<Void> clearPushTarget();

    @Operation(
            summary = "회원 탈퇴",
            description = "현재 계정의 PIN을 재확인한 뒤 계정을 비활성화하고 식별 정보를 익명화합니다. "
                    + "미완료 정산이 있거나 그룹 OWNER인 경우 탈퇴할 수 없습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "회원 탈퇴 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_PIN_FORMAT - PIN 형식 오류"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "USER_DELETION_PIN_MISMATCH - 탈퇴 PIN 불일치"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "USER_NOT_FOUND - 사용자를 찾을 수 없음"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "USER_DELETION_GROUP_OWNER_TRANSFER_REQUIRED 또는 "
                            + "USER_DELETION_BLOCKED_BY_SETTLEMENT - 탈퇴 차단"
            )
    })
    ApiResponse<Void> deleteMyAccount(DeleteMyAccountWebRequest request);
}
