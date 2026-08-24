package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementLinkEntryDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementLinkEntryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementLinkParticipantSelectionWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.ResolveSettlementLinkUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SelectSettlementLinkParticipantUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlement-links")
public class SettlementLinkEntryController implements SettlementLinkEntryDocs {
    private final ResolveSettlementLinkUseCase resolveSettlementLinkUseCase;
    private final SelectSettlementLinkParticipantUseCase selectSettlementLinkParticipantUseCase;

    @Override
    @GetMapping("/{token}")
    public ApiResponse<SettlementLinkEntryWebResponse> resolve(@PathVariable String token) {
        return ApiResponse.success(SettlementLinkEntryWebResponse.from(
                resolveSettlementLinkUseCase.resolve(token)
        ));
    }

    @Override
    @PostMapping("/{token}/participants/{participantId}/select")
    public ApiResponse<SettlementLinkParticipantSelectionWebResponse> selectParticipant(
            @PathVariable String token,
            @PathVariable UUID participantId
    ) {
        return ApiResponse.success(SettlementLinkParticipantSelectionWebResponse.from(
                selectSettlementLinkParticipantUseCase.select(token, participantId)
        ));
    }
}
