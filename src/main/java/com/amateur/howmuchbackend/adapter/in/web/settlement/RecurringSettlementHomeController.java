package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.RecurringSettlementHomeDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementSummaryWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.GetRecurringSettlementHomeUseCase;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/recurring-settlements")
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementHomeController implements RecurringSettlementHomeDocs {
    private final GetRecurringSettlementHomeUseCase home;

    @Override
    @GetMapping("/home")
    public ApiResponse<List<RecurringSettlementSummaryWebResponse>> home(
            @RequestParam(defaultValue = "5") int limit) {
        // The 1..20 bound is enforced by the Application service, which rejects out-of-range limits with 400.
        return ApiResponse.success(home.getHome(limit).stream()
                .map(RecurringSettlementSummaryWebResponse::from).toList());
    }
}
