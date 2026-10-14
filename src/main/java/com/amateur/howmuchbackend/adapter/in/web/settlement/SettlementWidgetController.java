package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementWidgetDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementWidgetSummaryWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementWidgetSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RefreshSettlementWidgetSummaryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements/widget-summary")
public class SettlementWidgetController implements SettlementWidgetDocs {
    private final GetSettlementWidgetSummaryUseCase getUseCase;
    private final RefreshSettlementWidgetSummaryUseCase refreshUseCase;

    @Override
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SettlementWidgetSummaryWebResponse> getSummary() {
        return ApiResponse.success(SettlementWidgetSummaryWebResponse.from(getUseCase.getSummary()));
    }

    @Override
    @PostMapping(value = "/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SettlementWidgetSummaryWebResponse> refresh() {
        return ApiResponse.success(SettlementWidgetSummaryWebResponse.from(refreshUseCase.refresh()));
    }
}
