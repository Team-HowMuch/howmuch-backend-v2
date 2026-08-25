package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementDepositDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementDepositSummaryWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementDepositSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.ReconcileSettlementDepositsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements/{settlementId}/deposits")
public class SettlementDepositController implements SettlementDepositDocs {
    private final ReconcileSettlementDepositsUseCase reconcileUseCase;
    private final GetSettlementDepositSummaryUseCase getSummaryUseCase;

    @Override
    @PostMapping("/reconcile")
    public ApiResponse<SettlementDepositSummaryWebResponse> reconcile(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementDepositSummaryWebResponse.from(
                reconcileUseCase.reconcile(settlementId)
        ));
    }

    @Override
    @GetMapping
    public ApiResponse<SettlementDepositSummaryWebResponse> get(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementDepositSummaryWebResponse.from(
                getSummaryUseCase.get(settlementId)
        ));
    }
}
