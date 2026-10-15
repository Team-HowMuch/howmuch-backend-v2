package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementUnpaidSummaryDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementUnpaidSummaryWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementUnpaidSummaryUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements/unpaid-summary")
public class SettlementUnpaidSummaryController implements SettlementUnpaidSummaryDocs {
    private final GetSettlementUnpaidSummaryUseCase getSettlementUnpaidSummaryUseCase;

    @Override
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<SettlementUnpaidSummaryWebResponse> getSummary() {
        return ApiResponse.success(SettlementUnpaidSummaryWebResponse.from(
                getSettlementUnpaidSummaryUseCase.getSummary()
        ));
    }
}
