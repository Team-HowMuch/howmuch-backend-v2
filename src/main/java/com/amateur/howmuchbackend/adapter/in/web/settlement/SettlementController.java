package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.AddSettlementParticipantWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.UpdateSettlementDraftWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementParticipantListItemWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementParticipantWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.AddSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementParticipantsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveSettlementParticipantUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDraftUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements")
public class SettlementController implements SettlementDocs {
    private final CreateSettlementUseCase createSettlementUseCase;
    private final GetSettlementUseCase getSettlementUseCase;
    private final UpdateSettlementDraftUseCase updateSettlementDraftUseCase;
    private final AddSettlementParticipantUseCase addSettlementParticipantUseCase;
    private final RemoveSettlementParticipantUseCase removeSettlementParticipantUseCase;
    private final GetSettlementParticipantsUseCase getSettlementParticipantsUseCase;

    @Override
    @PostMapping
    public ApiResponse<SettlementWebResponse> createSettlement(
            @Valid @RequestBody CreateSettlementWebRequest request
    ) {
        return ApiResponse.success(SettlementWebResponse.from(
                createSettlementUseCase.create(request.toCommand())
        ));
    }

    @Override
    @GetMapping("/{settlementId}")
    public ApiResponse<SettlementWebResponse> getSettlement(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementWebResponse.from(
                getSettlementUseCase.get(settlementId)
        ));
    }

    @Override
    @PatchMapping("/{settlementId}")
    public ApiResponse<SettlementWebResponse> updateSettlement(
            @PathVariable UUID settlementId,
            @Valid @RequestBody UpdateSettlementDraftWebRequest request
    ) {
        return ApiResponse.success(SettlementWebResponse.from(
                updateSettlementDraftUseCase.update(settlementId, request.toCommand())
        ));
    }

    @Override
    @PostMapping("/{settlementId}/participants")
    public ApiResponse<SettlementParticipantWebResponse> addSettlementParticipant(
            @PathVariable UUID settlementId,
            @Valid @RequestBody AddSettlementParticipantWebRequest request
    ) {
        return ApiResponse.success(SettlementParticipantWebResponse.from(
                addSettlementParticipantUseCase.add(settlementId, request.toCommand())
        ));
    }

    @Override
    @GetMapping("/{settlementId}/participants")
    public ApiResponse<List<SettlementParticipantListItemWebResponse>> getSettlementParticipants(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(getSettlementParticipantsUseCase.get(settlementId)
                .stream()
                .map(SettlementParticipantListItemWebResponse::from)
                .toList());
    }

    @Override
    @DeleteMapping("/{settlementId}/participants/{participantId}")
    public ApiResponse<Void> removeSettlementParticipant(
            @PathVariable UUID settlementId,
            @PathVariable UUID participantId
    ) {
        removeSettlementParticipantUseCase.remove(settlementId, participantId);
        return ApiResponse.success();
    }
}
