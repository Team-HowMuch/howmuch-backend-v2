package com.amateur.howmuchbackend.adapter.in.web.settlement;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.docs.SettlementReminderDocs;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementBulkReminderWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response.SettlementReminderWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.RemindAllSettlementParticipantsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.RemindSettlementParticipantUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/settlements")
public class SettlementReminderController implements SettlementReminderDocs {
    private final RemindSettlementParticipantUseCase remindSettlementParticipantUseCase;
    private final RemindAllSettlementParticipantsUseCase remindAllSettlementParticipantsUseCase;

    @Override
    @PostMapping("/{settlementId}/participants/{participantId}/reminders")
    public ApiResponse<SettlementReminderWebResponse> remind(
            @PathVariable UUID settlementId,
            @PathVariable UUID participantId
    ) {
        return ApiResponse.success(SettlementReminderWebResponse.from(
                remindSettlementParticipantUseCase.remind(settlementId, participantId)
        ));
    }

    @Override
    @PostMapping("/{settlementId}/reminders")
    public ApiResponse<SettlementBulkReminderWebResponse> remindAll(
            @PathVariable UUID settlementId
    ) {
        return ApiResponse.success(SettlementBulkReminderWebResponse.from(
                remindAllSettlementParticipantsUseCase.remindAll(settlementId)
        ));
    }
}
