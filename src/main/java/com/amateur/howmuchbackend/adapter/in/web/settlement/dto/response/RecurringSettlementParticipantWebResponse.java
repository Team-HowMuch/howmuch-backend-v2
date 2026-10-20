package com.amateur.howmuchbackend.adapter.in.web.settlement.dto.response;

import com.amateur.howmuchbackend.application.port.in.settlement.result.RecurringSettlementPresentationResult;

public record RecurringSettlementParticipantWebResponse(Long userId, long amount, int displayOrder,
        String name, String profileImageUrl) {
    public static RecurringSettlementParticipantWebResponse from(Long groupId, RecurringSettlementPresentationResult.Participant value) {
        String url = value.avatarVersion() == null ? null : value.self()
                ? "/api/v2/user/me/avatar?v=" + value.avatarVersion()
                : "/api/v2/groups/%s/members/%s/avatar?v=%s".formatted(groupId, value.memberId(), value.avatarVersion());
        return new RecurringSettlementParticipantWebResponse(value.userId(), value.amount(), value.displayOrder(), value.name(), url);
    }
}
