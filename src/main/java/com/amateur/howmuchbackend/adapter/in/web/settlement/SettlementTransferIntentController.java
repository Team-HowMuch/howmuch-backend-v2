package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementTransferIntentDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementTransferIntentWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.RecordMySettlementTransferIntentUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RecordSettlementTransferIntentUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SettlementTransferIntentController implements SettlementTransferIntentDocs {
    private final RecordMySettlementTransferIntentUseCase member;
    private final RecordSettlementTransferIntentUseCase link;

    @Override
    @PostMapping("/api/v2/settlements/{settlementId}/my-transfer-intent")
    public ApiResponse<SettlementTransferIntentWebResponse> recordMyIntent(@PathVariable UUID settlementId) {
        return ApiResponse.of(202, SettlementTransferIntentWebResponse.from(member.recordMyIntent(settlementId)));
    }

    @Override
    @PostMapping("/api/v2/settlement-links/{token}/transfer-intent")
    public ApiResponse<SettlementTransferIntentWebResponse> recordIntent(@PathVariable String token,
            @RequestHeader("Link-Participant-Context") UUID participantContext) {
        return ApiResponse.of(202, SettlementTransferIntentWebResponse.from(link.recordIntent(token, participantContext)));
    }
}
