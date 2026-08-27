package com.amateur.howmuchbackend.adapter.in.web.treasurer;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.docs.TreasurerDocs;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.request.AskTreasurerWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerAnswerWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerChatWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerRecommendationsWebResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.response.TreasurerSummaryWebResponse;
import com.amateur.howmuchbackend.application.port.in.treasurer.AskTreasurerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerChatUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerRecommendationsUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerSummaryUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/treasurer")
public class TreasurerController implements TreasurerDocs {
    private final GetTreasurerChatUseCase getTreasurerChatUseCase;
    private final GetTreasurerSummaryUseCase getTreasurerSummaryUseCase;
    private final AskTreasurerUseCase askTreasurerUseCase;
    private final GetTreasurerRecommendationsUseCase getTreasurerRecommendationsUseCase;

    @Override
    @GetMapping("/chat")
    public ApiResponse<TreasurerChatWebResponse> getChat() {
        return ApiResponse.success(TreasurerChatWebResponse.from(getTreasurerChatUseCase.getChat()));
    }

    @Override
    @GetMapping("/summary")
    public ApiResponse<TreasurerSummaryWebResponse> getSummary() {
        return ApiResponse.success(TreasurerSummaryWebResponse.from(getTreasurerSummaryUseCase.getSummary()));
    }

    @Override
    @PostMapping("/questions")
    public ApiResponse<TreasurerAnswerWebResponse> ask(@Valid @RequestBody AskTreasurerWebRequest request) {
        return ApiResponse.success(TreasurerAnswerWebResponse.from(askTreasurerUseCase.ask(request.toCommand())));
    }

    @Override
    @GetMapping("/recommendations")
    public ApiResponse<TreasurerRecommendationsWebResponse> getRecommendations() {
        return ApiResponse.success(
                TreasurerRecommendationsWebResponse.from(getTreasurerRecommendationsUseCase.getRecommendations())
        );
    }
}
