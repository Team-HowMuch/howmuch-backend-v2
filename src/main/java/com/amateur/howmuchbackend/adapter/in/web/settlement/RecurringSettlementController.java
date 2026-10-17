package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.RecurringSettlementDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.request.CreateRecurringSettlementWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementOccurrenceWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementPageWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementSchedulePreviewWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementSummaryWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.RecurringSettlementWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.CancelRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.CreateRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetGroupRecurringSettlementsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetRecurringSettlementOccurrencesUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetRecurringSettlementSchedulePreviewUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.GetRecurringSettlementUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.RecurringSettlementPageCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.command.RecurringSettlementSchedulePreviewCommand;
import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementScope;
import com.amateur.howmuchbackend.application.settlement.model.RecurringSettlementStatusFilter;
import com.amateur.howmuchbackend.domain.settlement.recurring.RecurrenceSchedule;
import jakarta.validation.Valid;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/groups/{groupId}/recurring-settlements")
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = "enabled", havingValue = "true")
public class RecurringSettlementController implements RecurringSettlementDocs {
    private final CreateRecurringSettlementUseCase create;
    private final GetGroupRecurringSettlementsUseCase list;
    private final GetRecurringSettlementUseCase get;
    private final GetRecurringSettlementOccurrencesUseCase history;
    private final CancelRecurringSettlementUseCase cancel;
    private final GetRecurringSettlementSchedulePreviewUseCase preview;

    @Override
    @PostMapping
    public ApiResponse<RecurringSettlementWebResponse> create(@PathVariable Long groupId,
            @Valid @RequestBody CreateRecurringSettlementWebRequest request) {
        return ApiResponse.success(RecurringSettlementWebResponse.from(create.create(groupId, request.toCommand())));
    }

    @Override
    @GetMapping
    public ApiResponse<RecurringSettlementPageWebResponse<RecurringSettlementSummaryWebResponse>> list(
            @PathVariable Long groupId,
            @RequestParam(required = false) RecurringSettlementScope scope,
            @RequestParam(required = false) RecurringSettlementStatusFilter status,
            @RequestParam(required = false) UUID cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(RecurringSettlementPageWebResponse.from(
                list.get(groupId, new RecurringSettlementPageCommand(cursor, limit, scope, status)),
                RecurringSettlementSummaryWebResponse::from));
    }

    @Override
    @GetMapping("/schedule-preview")
    public ApiResponse<RecurringSettlementSchedulePreviewWebResponse> preview(@PathVariable Long groupId,
            @RequestParam RecurrenceSchedule.Frequency frequency,
            @RequestParam(required = false) DayOfWeek dayOfWeek,
            @RequestParam(required = false) Integer dayOfMonth,
            @RequestParam(required = false) LocalDate firstRunDate) {
        return ApiResponse.success(RecurringSettlementSchedulePreviewWebResponse.from(
                preview.preview(groupId, new RecurringSettlementSchedulePreviewCommand(frequency, dayOfWeek,
                        dayOfMonth, firstRunDate))));
    }

    @Override
    @GetMapping("/{id}")
    public ApiResponse<RecurringSettlementWebResponse> get(@PathVariable Long groupId, @PathVariable UUID id) {
        return ApiResponse.success(RecurringSettlementWebResponse.from(get.get(groupId, id)));
    }

    @Override
    @GetMapping("/{id}/occurrences")
    public ApiResponse<RecurringSettlementPageWebResponse<RecurringSettlementOccurrenceWebResponse>> occurrences(
            @PathVariable Long groupId, @PathVariable UUID id,
            @RequestParam(required = false) UUID cursor, @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(RecurringSettlementPageWebResponse.from(
                history.get(groupId, id, new RecurringSettlementPageCommand(cursor, limit, null, null)),
                RecurringSettlementOccurrenceWebResponse::from));
    }

    @Override
    @PostMapping("/{id}/cancel")
    public ApiResponse<RecurringSettlementWebResponse> cancel(@PathVariable Long groupId, @PathVariable UUID id) {
        return ApiResponse.success(RecurringSettlementWebResponse.from(cancel.cancel(groupId, id)));
    }
}
