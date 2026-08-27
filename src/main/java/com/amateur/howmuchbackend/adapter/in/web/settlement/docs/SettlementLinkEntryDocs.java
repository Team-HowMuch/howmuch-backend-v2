package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementLinkEntryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementLinkParticipantSelectionWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "공개 정산 링크 API", description = "공통 링크 진입과 참여자 선택 API")
public interface SettlementLinkEntryDocs {
    @Operation(
            summary = "공통 정산 링크 조회",
            description = "인증 없이 참여자 이름 목록을 조회할 수 있습니다. 유효한 Bearer 인증이 있고 "
                    + "회원 참여자와 연결되면 APP_DIRECT와 단기 참여 문맥을 반환합니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "공통 링크 조회 또는 앱 회원 자동 매칭 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_LINK_NOT_FOUND - 형식 오류·만료·비활성·사용 불가 링크"
            )
    })
    ApiResponse<SettlementLinkEntryWebResponse> resolve(
            @Parameter(description = "공통 URL의 43자 불투명 token") String token
    );

    @Operation(
            summary = "공통 링크 참여자 선택",
            description = "브라우저 사용자가 본인이라고 판단한 참여자를 신뢰 기반으로 선택하고 "
                    + "30분 단기 참여 문맥을 발급합니다. 오선택·사칭 방지는 MVP 범위에 포함하지 않습니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "참여자 선택과 단기 참여 문맥 발급 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_LINK_NOT_FOUND 또는 SETTLEMENT_PARTICIPANT_NOT_FOUND"
            )
    })
    ApiResponse<SettlementLinkParticipantSelectionWebResponse> selectParticipant(
            @Parameter(description = "공통 URL의 43자 불투명 token") String token,
            @Parameter(description = "사용자가 선택한 정산 참여자 ID") UUID participantId
    );
}
