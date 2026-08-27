package com.amateur.howmuchbackend.application.port.in.treasurer.result;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;

import java.util.List;

public record TreasurerChatResult(
        String greeting,
        List<Prompt> quickActions,
        List<Prompt> suggestions
) {
    public record Prompt(
            InquiryIntent type,
            String label,
            String question
    ) {
    }
}
