package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateSettlementWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateSettlementUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements")
public class SettlementController implements SettlementDocs {
    private final CreateSettlementUseCase createSettlementUseCase;

    @Override
    @PostMapping
    public ApiResponse<SettlementWebResponse> createSettlement(
            @Valid @RequestBody CreateSettlementWebRequest request
    ) {
        return ApiResponse.success(SettlementWebResponse.from(
                createSettlementUseCase.create(request.toCommand())
        ));
    }
}
