package com.amateur.howmuchbackend.adapter.out.llm;

import com.amateur.howmuchbackend.application.treasurer.model.TreasurerAnswerDraft;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.infra.treasurer.config.TreasurerLlmProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TreasurerLlmAdapterTest {
    @Mock
    private RestClient restClient;

    @Test
    void treasurerLlm_disabledProvider_returnsEmpty() {
        TreasurerLlmAdapter adapter = adapter("none");
        TreasurerAnswerDraft draft = draft();

        assertThat(adapter.generate(draft)).isEmpty();
    }

    @Test
    void treasurerLlm_clientFailure_returnsEmpty() {
        TreasurerLlmProperties properties = new TreasurerLlmProperties(
                "openai",
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                new TreasurerLlmProperties.OpenAi("test-key", "gpt-4o-mini", "https://api.openai.com/v1"),
                new TreasurerLlmProperties.Gemini("", "gemini-flash-latest", "https://generativelanguage.googleapis.com")
        );
        TreasurerLlmAdapter adapter = new TreasurerLlmAdapter(restClient, properties, new ObjectMapper());

        assertThat(adapter.generate(draft())).isEmpty();
    }

    @Test
    void returnsEmptyWhenGeminiKeyIsMissing() {
        TreasurerLlmAdapter adapter = adapter("gemini");

        assertThat(adapter.generate(draft())).isEmpty();
    }

    @Test
    void returnsEmptyWhenDraftIsMissing() {
        TreasurerLlmAdapter adapter = adapter("none");

        assertThat(adapter.generate(null)).isEmpty();
    }

    private TreasurerLlmAdapter adapter(String provider) {
        TreasurerLlmProperties properties = new TreasurerLlmProperties(
                provider,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                new TreasurerLlmProperties.OpenAi("", "gpt-4o-mini", "https://api.openai.com/v1"),
                new TreasurerLlmProperties.Gemini("", "gemini-flash-latest", "https://generativelanguage.googleapis.com")
        );
        return new TreasurerLlmAdapter(restClient, properties, new ObjectMapper());
    }

    private TreasurerAnswerDraft draft() {
        return new TreasurerAnswerDraft(
                "이번 달 보낼 정산은 얼마야?",
                InquiryIntent.SEND_SETTLEMENTS,
                "이번 달 기준으로 조회했어요.",
                "이번 달 기준으로 조회했어요. 보낼 정산은 1건, 총 12,000원이에요.",
                12_000L,
                1,
                List.of()
        );
    }
}
