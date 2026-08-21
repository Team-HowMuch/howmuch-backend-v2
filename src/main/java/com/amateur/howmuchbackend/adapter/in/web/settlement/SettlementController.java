package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.UpdateSettlementDraftWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDraftUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements")
public class SettlementController implements SettlementDocs {
    private final CreateSettlementUseCase createSettlementUseCase;
    private final GetSettlementUseCase getSettlementUseCase;
    private final UpdateSettlementDraftUseCase updateSettlementDraftUseCase;

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
}
