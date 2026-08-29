package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementRequestDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementRequestWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementShareLinkWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementShareLinkUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RequestSettlementUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements")
public class SettlementRequestController implements SettlementRequestDocs {
    private final RequestSettlementUseCase requestSettlementUseCase;
    private final GetSettlementShareLinkUseCase getSettlementShareLinkUseCase;

    @Override
    @PostMapping("/{settlementId}/request")
    public ApiResponse<SettlementRequestWebResponse> requestSettlement(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementRequestWebResponse.from(
                requestSettlementUseCase.request(settlementId)
        ));
    }

    @Override
    @GetMapping("/{settlementId}/share-link")
    public ApiResponse<SettlementShareLinkWebResponse> getShareLink(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementShareLinkWebResponse.from(
                getSettlementShareLinkUseCase.get(settlementId)
        ));
    }
}
