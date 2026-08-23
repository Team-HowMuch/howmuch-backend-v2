package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementAllocationDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SelectSettlementAllocationTypeWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.UpdateSettlementDutchPayAllocationWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementAllocationTypeWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementDutchPayWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementDutchPayAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SelectSettlementAllocationTypeUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.UpdateSettlementDutchPayAllocationUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements/{settlementId}")
public class SettlementAllocationController implements SettlementAllocationDocs {
    private final SelectSettlementAllocationTypeUseCase selectAllocationTypeUseCase;
    private final GetSettlementDutchPayAllocationUseCase getDutchPayAllocationUseCase;
    private final UpdateSettlementDutchPayAllocationUseCase updateDutchPayAllocationUseCase;

    @Override
    @PutMapping("/allocation-type")
    public ApiResponse<SettlementAllocationTypeWebResponse> selectAllocationType(
            @PathVariable UUID settlementId,
            @Valid @RequestBody SelectSettlementAllocationTypeWebRequest request
    ) {
        return ApiResponse.success(SettlementAllocationTypeWebResponse.from(
                selectAllocationTypeUseCase.select(settlementId, request.toCommand())
        ));
    }

    @Override
    @GetMapping("/dutch-pay-allocations")
    public ApiResponse<SettlementDutchPayWebResponse> getDutchPayAllocation(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementDutchPayWebResponse.from(
                getDutchPayAllocationUseCase.get(settlementId)
        ));
    }

    @Override
    @PatchMapping("/dutch-pay-allocations/{participantId}")
    public ApiResponse<SettlementDutchPayWebResponse> updateDutchPayAllocation(
            @PathVariable UUID settlementId,
            @PathVariable UUID participantId,
            @Valid @RequestBody UpdateSettlementDutchPayAllocationWebRequest request
    ) {
        return ApiResponse.success(SettlementDutchPayWebResponse.from(
                updateDutchPayAllocationUseCase.update(
                        settlementId,
                        participantId,
                        request.toCommand()
                )
        ));
    }
}
