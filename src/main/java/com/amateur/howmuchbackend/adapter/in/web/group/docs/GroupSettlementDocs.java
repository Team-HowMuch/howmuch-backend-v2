package com.amateur.howmuchbackend.adapter.in.web.group.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.request.GroupSettlementListWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.group.dto.response.GroupSettlementListItemWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;

@Tag(name = "그룹 API")
public interface GroupSettlementDocs {
    @Operation(operationId = "getGroupSettlements", summary = "그룹 전체 정산 목록 조회",
            description = "활성 그룹원이 그룹 출처 참여자가 있는 정산을 요청 시각·ID 내림차순으로 조회합니다. "
                    + "제3자 정산도 포함하며 DRAFT·삭제된 정산·압축에 포함된 원본은 제외합니다. "
                    + "기본 상태는 IN_PROGRESS이며 기존 개인 목록과 같은 상태 필터를 제공합니다. "
                    + "viewerRole은 REQUESTER/PARTICIPANT/NONE입니다. 카테고리는 요청자 기준입니다. "
                    + "개인 받을 금액·독촉 시각은 요청자, 개인 납부액은 참여자에게만 반환합니다. "
                    + "계좌번호·전화번호·송금 정보는 제공하지 않으며 정산 상세·액션 권한은 기존과 같습니다. "
                    + "페이지네이션 없는 배열이며 빈 결과는 빈 배열입니다.",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "그룹 정산 목록 조회 성공", useReturnTypeSchema = true),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 상태 필터 또는 0 이하 그룹 ID"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "인증 필요"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "활성 그룹원만 조회 가능"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "그룹을 찾을 수 없음")
    })
    ApiResponse<List<GroupSettlementListItemWebResponse>> get(
            @Positive Long groupId, @Valid GroupSettlementListWebRequest request);
}
