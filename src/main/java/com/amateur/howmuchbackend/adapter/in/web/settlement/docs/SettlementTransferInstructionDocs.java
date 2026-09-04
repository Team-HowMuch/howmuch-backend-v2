package com.amateur.howmuchbackend.adapter.in.web.settlement.docs;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementTransferInstructionWebResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

@Tag(name = "공개 정산 송금 안내 API", description = "확정 정산 참여자의 송금 금액·수금 계좌 조회 API")
public interface SettlementTransferInstructionDocs {
    @Operation(
            summary = "확정 정산 송금 안내 조회",
            description = "링크 참여 문맥이 가리키는 참여자의 확정 Share와 수금 계좌를 반환합니다. "
                    + "transferLinks는 설정에 따라 Toss 딥링크 또는 HTTPS Mock 송금 화면 URL을 반환합니다. "
                    + "Mock에서도 provider는 TOSS이며 클라이언트는 url을 그대로 엽니다. "
                    + "서버가 송금을 실행하거나 링크 조회로 납부 상태를 변경하지는 않습니다."
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "송금 금액과 수금 계좌 조회 성공",
                    useReturnTypeSchema = true
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "SETTLEMENT_LINK_NOT_FOUND, SETTLEMENT_PARTICIPANT_CONTEXT_NOT_FOUND "
                            + "또는 SETTLEMENT_TRANSFER_INSTRUCTION_NOT_FOUND"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "422",
                    description = "SETTLEMENT_TRANSFER_LINK_NOT_SUPPORTED"
            )
    })
    ApiResponse<SettlementTransferInstructionWebResponse> get(
            @Parameter(description = "공통 URL의 43자 불투명 token") String token,
            @Parameter(
                    name = "Link-Participant-Context",
                    in = ParameterIn.HEADER,
                    description = "링크 진입 후 발급된 단기 참여 문맥",
                    required = true
            ) UUID participantContext
    );
}
