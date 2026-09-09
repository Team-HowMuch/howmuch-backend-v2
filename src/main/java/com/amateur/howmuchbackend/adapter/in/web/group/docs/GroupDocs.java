package com.amateur.howmuchbackend.adapter.in.web.group.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.CreateGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.AddGroupMemberWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.JoinGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.UpdateGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.TransferGroupOwnershipWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupDetailWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupSummaryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupMemberWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "그룹 API", description = "그룹 생성, 조회와 설정 API")
public interface GroupDocs {
    @Operation(
            summary = "그룹 생성",
            description = "그룹과 요청자의 OWNER 멤버십을 함께 생성합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "그룹 생성 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_GROUP_NAME, INVALID_GROUP_MEMO 또는 INVALID_GROUP_IMAGE"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "UNAUTHORIZED - 인증 필요"
            )
    })
    ApiResponse<GroupDetailWebResponse> createGroup(CreateGroupWebRequest request);

    @Operation(
            summary = "내 그룹 목록 조회",
            description = "현재 사용자의 활성 그룹 전체를 최근 가입 순으로 조회합니다. "
                    + "memberCount는 본인을 포함한 활성 사용자·활성 멤버십의 전체 수이며 탈퇴·강퇴·비활성 사용자는 제외합니다. "
                    + "memberNames는 그룹장 우선, 가입 시각·멤버 ID 오름차순으로 최대 3개를 반환합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    ApiResponse<List<GroupSummaryWebResponse>> getMyGroups();

    @Operation(
            summary = "그룹 상세 조회",
            description = "활성 멤버가 그룹 정보, 인원수와 초대 정보를 조회합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "그룹 상세 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "GROUP_ACCESS_DENIED - 활성 멤버가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "GROUP_NOT_FOUND - 그룹이 없거나 삭제됨"
            )
    })
    ApiResponse<GroupDetailWebResponse> getGroup(Long groupId);

    @Operation(
            summary = "그룹 이미지 조회",
            description = "활성 멤버가 등록된 그룹 이미지를 조회합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    content = @Content(schema = @Schema(type = "string", format = "binary"))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "GROUP_ACCESS_DENIED - 활성 멤버가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "GROUP_NOT_FOUND 또는 GROUP_IMAGE_NOT_FOUND"
            )
    })
    ResponseEntity<byte[]> getGroupImage(Long groupId);

    @Operation(
            summary = "그룹 설정 수정",
            description = "OWNER가 이름, 메모 또는 이미지를 부분 수정합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "그룹 설정 수정 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_GROUP_NAME, INVALID_GROUP_MEMO, INVALID_GROUP_IMAGE 또는 INVALID_GROUP_UPDATE"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "GROUP_ACCESS_DENIED 또는 GROUP_OWNER_REQUIRED"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "GROUP_NOT_FOUND - 그룹이 없거나 삭제됨"
            )
    })
    ApiResponse<GroupDetailWebResponse> updateGroup(Long groupId, UpdateGroupWebRequest request);

    @Operation(
            summary = "그룹 삭제",
            description = "OWNER가 그룹을 소프트 삭제합니다. 기존 멤버십과 정산 데이터는 보존합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "그룹 삭제 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "GROUP_ACCESS_DENIED 또는 GROUP_OWNER_REQUIRED"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "GROUP_NOT_FOUND - 그룹이 없거나 이미 삭제됨"
            )
    })
    ApiResponse<Void> deleteGroup(Long groupId);

    @Operation(
            summary = "그룹 가입",
            description = "초대 코드로 그룹에 가입하거나 LEFT 멤버십을 재활성화합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "그룹 가입 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "INVALID_GROUP_INVITE_CODE"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "GROUP_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "GROUP_MEMBER_ALREADY_ACTIVE 또는 GROUP_MEMBER_REMOVED"
            )
    })
    ApiResponse<GroupDetailWebResponse> joinGroup(JoinGroupWebRequest request);

    @Operation(
            summary = "그룹 멤버 목록 조회",
            description = "활성 멤버가 그룹의 활성 구성원 전체를 조회합니다. "
                    + "프로필 사진이 있는 멤버는 avatarUrl이 채워지고, 없으면 null입니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "그룹 멤버 목록 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "GROUP_ACCESS_DENIED"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "GROUP_NOT_FOUND"
            )
    })
    ApiResponse<List<GroupMemberWebResponse>> getGroupMembers(Long groupId);

    @Operation(
            summary = "그룹 멤버 프로필 사진 조회",
            description = "활성 멤버가 같은 그룹 구성원의 프로필 사진을 조회합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    content = @Content(schema = @Schema(type = "string", format = "binary"))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "GROUP_ACCESS_DENIED - 활성 멤버가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "GROUP_NOT_FOUND, GROUP_MEMBER_NOT_FOUND 또는 PROFILE_IMAGE_NOT_FOUND"
            )
    })
    ResponseEntity<byte[]> getGroupMemberAvatar(Long groupId, Long memberId);

    @Operation(
            summary = "그룹 멤버 직접 추가",
            description = "OWNER가 공개 ID로 새 멤버를 추가하거나 REMOVED 멤버십을 복구합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "그룹 멤버 추가 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "GROUP_ACCESS_DENIED 또는 GROUP_OWNER_REQUIRED"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "GROUP_NOT_FOUND 또는 GROUP_MEMBER_CANDIDATE_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "GROUP_MEMBER_ALREADY_ACTIVE 또는 GROUP_MEMBER_LEFT_REQUIRES_INVITE"
            )
    })
    ApiResponse<GroupMemberWebResponse> addGroupMember(
            Long groupId,
            AddGroupMemberWebRequest request
    );

    @Operation(
            summary = "그룹 소유권 이전",
            description = "현재 OWNER가 같은 그룹의 활성 MEMBER에게 소유권을 이전합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "그룹 소유권 이전 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "GROUP_ACCESS_DENIED 또는 GROUP_OWNER_REQUIRED"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "GROUP_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "GROUP_OWNER_TRANSFER_TARGET_INVALID"
            )
    })
    ApiResponse<Void> transferGroupOwnership(
            Long groupId,
            TransferGroupOwnershipWebRequest request
    );

    @Operation(
            summary = "그룹 자기 탈퇴",
            description = "현재 MEMBER가 진행 중인 그룹 출처 정산이 없을 때 그룹을 탈퇴합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "그룹 탈퇴 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "GROUP_ACCESS_DENIED"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "GROUP_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "GROUP_OWNER_TRANSFER_REQUIRED 또는 GROUP_MEMBER_EXIT_BLOCKED_BY_SETTLEMENT"
            )
    })
    ApiResponse<Void> leaveGroup(Long groupId);

    @Operation(
            summary = "그룹 멤버 제거",
            description = "OWNER가 진행 중인 그룹 출처 정산이 없는 활성 MEMBER를 제거합니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "그룹 멤버 제거 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "GROUP_ACCESS_DENIED 또는 GROUP_OWNER_REQUIRED"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "GROUP_NOT_FOUND 또는 GROUP_MEMBER_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "GROUP_OWNER_TRANSFER_REQUIRED 또는 GROUP_MEMBER_EXIT_BLOCKED_BY_SETTLEMENT"
            )
    })
    ApiResponse<Void> removeGroupMember(Long groupId, Long memberId);
}
