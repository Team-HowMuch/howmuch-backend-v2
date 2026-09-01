package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementMemberItemAllocationDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SaveSettlementItemSelectionWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementItemAllocationWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.ResolveMyItemAllocationUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements/{settlementId}")
public class SettlementMemberItemAllocationController
        implements SettlementMemberItemAllocationDocs {
    private final ResolveMyItemAllocationUseCase resolveMyItemAllocationUseCase;

    @Override
    @GetMapping("/my-item-selection")
    public ApiResponse<SettlementItemAllocationWebResponse> getMyAllocation(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                resolveMyItemAllocationUseCase.getMyAllocation(settlementId)
        ));
    }

    @Override
    @PutMapping("/my-item-selection/{itemId}")
    public ApiResponse<SettlementItemAllocationWebResponse> saveMySelection(
            @PathVariable UUID settlementId,
            @PathVariable UUID itemId,
            @Valid @RequestBody SaveSettlementItemSelectionWebRequest request
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                resolveMyItemAllocationUseCase.saveMySelection(
                        settlementId, itemId, request.toCommand()
                )
        ));
    }

    @Override
    @DeleteMapping("/my-item-selection/{itemId}")
    public ApiResponse<SettlementItemAllocationWebResponse> removeMySelection(
            @PathVariable UUID settlementId,
            @PathVariable UUID itemId
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                resolveMyItemAllocationUseCase.removeMySelection(settlementId, itemId)
        ));
    }

    @Override
    @PostMapping("/my-item-selection/submission")
    public ApiResponse<SettlementItemAllocationWebResponse> submitMySelection(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                resolveMyItemAllocationUseCase.submitMySelection(settlementId)
        ));
    }
}
