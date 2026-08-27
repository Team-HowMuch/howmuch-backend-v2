package com.amateur.howmuchbackend.application.port.out.treasurer;

import java.util.List;

public interface GenerateTreasurerAnswerPort {
    String generate(TreasurerAnswerDraft draft);

    record TreasurerAnswerDraft(
            String question,
            String intent,
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
}
