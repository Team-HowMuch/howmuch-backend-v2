package com.amateur.howmuchbackend.application.treasurer.model;

import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;

import java.util.List;

public record TreasurerAnswerDraft(
        String question,
        InquiryIntent intent,
        String scope,
        String fallbackAnswer,
        long totalAmount,
        int itemCount,
        List<Item> items
) {
    public record Item(
            String title,
            String direction,
            long amount
    ) {
    }
}
