package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementTransferInstructionDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementTransferInstructionWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementTransferInstructionUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlement-links/{token}")
public class SettlementTransferInstructionController
        implements SettlementTransferInstructionDocs {
    private static final String PARTICIPANT_CONTEXT_HEADER =
            "Link-Participant-Context";

    private final GetSettlementTransferInstructionUseCase getUseCase;

    @Override
    @GetMapping("/transfer-instruction")
    public ApiResponse<SettlementTransferInstructionWebResponse> get(
            @PathVariable String token,
            @RequestHeader(PARTICIPANT_CONTEXT_HEADER) UUID participantContext
    ) {
        return ApiResponse.success(SettlementTransferInstructionWebResponse.from(
                getUseCase.get(token, participantContext)
        ));
    }
}
