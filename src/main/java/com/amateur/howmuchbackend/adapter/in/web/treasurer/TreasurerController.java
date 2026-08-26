package com.amateur.howmuchbackend.adapter.in.web.treasurer;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.docs.TreasurerDocs;
import com.amateur.howmuchbackend.adapter.in.web.treasurer.dto.request.AskTreasurerWebRequest;
import com.amateur.howmuchbackend.application.port.in.treasurer.AskTreasurerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerRecommendationsUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerSummaryUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerAnswerResponse;
import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerRecommendationsResponse;
import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerSummaryResponse;
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
    private final GetTreasurerSummaryUseCase getTreasurerSummaryUseCase;
    private final AskTreasurerUseCase askTreasurerUseCase;
    private final GetTreasurerRecommendationsUseCase getTreasurerRecommendationsUseCase;

    @Override
    @GetMapping("/summary")
    public ApiResponse<TreasurerSummaryResponse> getSummary() {
        return ApiResponse.success(getTreasurerSummaryUseCase.getSummary());
    }

    @Override
    @PostMapping("/questions")
    public ApiResponse<TreasurerAnswerResponse> ask(@Valid @RequestBody AskTreasurerWebRequest request) {
        return ApiResponse.success(askTreasurerUseCase.ask(request.toRequest()));
    }

    @Override
    @GetMapping("/recommendations")
    public ApiResponse<TreasurerRecommendationsResponse> getRecommendations() {
        return ApiResponse.success(getTreasurerRecommendationsUseCase.getRecommendations());
    }
}
