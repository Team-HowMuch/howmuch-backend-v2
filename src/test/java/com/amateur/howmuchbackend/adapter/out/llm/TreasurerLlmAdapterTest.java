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
                new TreasurerLlmProperties.OpenAi("test-key", "gpt-6-luna", "https://api.openai.com/v1", "low"),
                new TreasurerLlmProperties.Gemini("", "gemini-flash-latest", "https://generativelanguage.googleapis.com")
        );
        TreasurerLlmAdapter adapter = new TreasurerLlmAdapter(restClient, properties, new ObjectMapper());

        assertThat(adapter.generate(draft())).isEmpty();
    }

    @Test
    void treasurerLlm_missingGeminiKey_returnsEmpty() {
        TreasurerLlmAdapter adapter = adapter("gemini");

        assertThat(adapter.generate(draft())).isEmpty();
    }

    @Test
    void treasurerLlm_missingDraft_returnsEmpty() {
        TreasurerLlmAdapter adapter = adapter("none");

        assertThat(adapter.generate(null)).isEmpty();
    }

    @Test
    void treasurerLlm_rejectedRequest_returnsEmpty() {
        TreasurerLlmProperties properties = new TreasurerLlmProperties(
                "openai",
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                new TreasurerLlmProperties.OpenAi("test-key", "gpt-6-luna", "https://api.openai.com/v1", "low"),
                new TreasurerLlmProperties.Gemini("", "gemini-flash-latest", "https://generativelanguage.googleapis.com")
        );
        org.mockito.Mockito.when(restClient.post()).thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "Unauthorized", null, new byte[0], null));
        TreasurerLlmAdapter adapter = new TreasurerLlmAdapter(restClient, properties, new ObjectMapper());

        assertThat(adapter.generate(draft())).isEmpty();
    }

    @Test
    void treasurerLlm_openAiBody_withoutTemperatureOrResponseFormat() {
        TreasurerLlmAdapter adapter = adapter("openai");

        var body = adapter.openAiBody(draft());

        assertThat(body).containsOnlyKeys("model", "reasoning_effort", "messages");
        assertThat(body).containsEntry("model", "gpt-6-luna").containsEntry("reasoning_effort", "low");
        assertThat(body.get("messages").toString()).contains("이번 달 보낼 정산은 얼마야?");
    }

    private TreasurerLlmAdapter adapter(String provider) {
        TreasurerLlmProperties properties = new TreasurerLlmProperties(
                provider,
                Duration.ofSeconds(5),
                Duration.ofSeconds(20),
                new TreasurerLlmProperties.OpenAi("", "gpt-6-luna", "https://api.openai.com/v1", "low"),
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
