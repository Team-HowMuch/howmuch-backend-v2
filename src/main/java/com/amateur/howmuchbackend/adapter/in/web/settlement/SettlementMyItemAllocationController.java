package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementMyItemAllocationDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.SaveSettlementItemSelectionWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementItemAllocationWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetMySettlementItemAllocationUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemoveMySettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SaveMySettlementItemSelectionUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SubmitMySettlementItemAllocationUseCase;
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
public class SettlementMyItemAllocationController implements SettlementMyItemAllocationDocs {
    private final GetMySettlementItemAllocationUseCase getMyItemsUseCase;
    private final SaveMySettlementItemSelectionUseCase saveMyItemUseCase;
    private final RemoveMySettlementItemSelectionUseCase removeMyItemUseCase;
    private final SubmitMySettlementItemAllocationUseCase submitMyItemsUseCase;

    @Override
    @GetMapping("/my-items")
    public ApiResponse<SettlementItemAllocationWebResponse> getMyItems(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                getMyItemsUseCase.getMyItems(settlementId)
        ));
    }

    @Override
    @PutMapping("/my-items/{itemId}")
    public ApiResponse<SettlementItemAllocationWebResponse> saveMyItem(
            @PathVariable UUID settlementId,
            @PathVariable UUID itemId,
            @Valid @RequestBody SaveSettlementItemSelectionWebRequest request
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                saveMyItemUseCase.saveMyItem(settlementId, itemId, request.toCommand())
        ));
    }

    @Override
    @DeleteMapping("/my-items/{itemId}")
    public ApiResponse<SettlementItemAllocationWebResponse> removeMyItem(
            @PathVariable UUID settlementId,
            @PathVariable UUID itemId
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                removeMyItemUseCase.removeMyItem(settlementId, itemId)
        ));
    }

    @Override
    @PostMapping("/my-item-submission")
    public ApiResponse<SettlementItemAllocationWebResponse> submitMyItems(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementItemAllocationWebResponse.from(
                submitMyItemsUseCase.submitMyItems(settlementId)
        ));
    }
}
