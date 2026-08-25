package com.amateur.howmuchbackend.adapter.in.web.group.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.CreateGroupWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupDetailWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupSummaryWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;

@Tag(name = "그룹 API", description = "그룹 생성과 조회 API")
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
            description = "현재 사용자의 활성 그룹 전체를 최근 가입 순으로 조회합니다.",
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
}
