package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementMemberTransferInstructionDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementTransferInstructionWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.ResolveMyTransferInstructionUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements/{settlementId}")
public class SettlementMemberTransferInstructionController
        implements SettlementMemberTransferInstructionDocs {
    private final ResolveMyTransferInstructionUseCase resolveMyTransferInstructionUseCase;

    @Override
    @GetMapping("/my-transfer-instruction")
    public ApiResponse<SettlementTransferInstructionWebResponse> getMyTransferInstruction(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementTransferInstructionWebResponse.from(
                resolveMyTransferInstructionUseCase.getMyTransferInstruction(settlementId)
        ));
    }
}
