package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.treasurer.policy.TreasurerCardCopy;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GetTreasurerChatServiceTest {
    @Test
    void treasurerChat_greetingAndSuggestions_returned() {
        GetTreasurerChatService service = new GetTreasurerChatService(new TreasurerCardCopy());

        var result = service.getChat();

        assertThat(result.greeting()).isEqualTo("안녕하세요. 어떤 정산이 궁금하세요?");
        assertThat(result.quickActions()).extracting(item -> item.label())
                .containsExactly("보낼 정산", "받을 정산", "정산 상태");
        assertThat(result.suggestions()).extracting(item -> item.question())
                .contains("이번 주에 정산 있어?", "OCR 정산은 어떻게 해?");
    }
}
