package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementRequestDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementRequestWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.RequestSettlementUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements")
public class SettlementRequestController implements SettlementRequestDocs {
    private final RequestSettlementUseCase requestSettlementUseCase;

    @Override
    @PostMapping("/{settlementId}/request")
    public ApiResponse<SettlementRequestWebResponse> requestSettlement(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementRequestWebResponse.from(
                requestSettlementUseCase.request(settlementId)
        ));
    }
}
