package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementTransferInstructionWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "회원 정산 송금 안내 API", description = "로그인 참여자가 링크 없이 자기 송금 안내를 보는 API")
public interface SettlementMemberTransferInstructionDocs {
    @Operation(
            summary = "내 송금 안내 조회",
            description = "로그인 회원과 연결된 참여자의 확정 Share와 수금 계좌를 반환합니다. "
                    + "링크 경로와 응답이 같으며 transferLinks는 설정에 따라 Toss 딥링크 또는 HTTPS Mock 송금 화면 URL을 반환합니다. "
                    + "Mock에서도 provider는 TOSS이며 클라이언트는 url을 그대로 엽니다. "
                    + "서버가 송금을 실행하거나 링크 조회로 납부 상태를 변경하지는 않습니다.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "송금 금액과 수금 계좌 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "SETTLEMENT_ACCESS_DENIED — 이 정산의 참여자가 아님"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_NOT_FOUND 또는 SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "SETTLEMENT_TRANSFER_LINK_NOT_SUPPORTED"
            )
    })
    ApiResponse<SettlementTransferInstructionWebResponse> getMyTransferInstruction(
            @Parameter(description = "정산 id") UUID settlementId
    );
}
