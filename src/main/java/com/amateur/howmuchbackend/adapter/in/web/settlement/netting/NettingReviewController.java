package com.amateur.howmuchbackend.adapter.in.web.settlement.netting;

import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.request.ResolveNettingReviewWebRequest;
import com.amateur.howmuchbackend.adapter.in.web.settlement.netting.dto.response.NettingReviewWebResponse;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.ResolveNettingDepositReviewUseCase;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v2/admin/settlement-netting-reviews")
@ConditionalOnProperty(
        prefix = "howmuch.settlement.netting",
        name = "enabled",
        havingValue = "true")
public class NettingReviewController implements NettingReviewDocs {
    private final ResolveNettingDepositReviewUseCase reviews;

    @GetMapping
    public ApiResponse<List<NettingReviewWebResponse>> list(@RequestParam UUID nettingId) {
        return ApiResponse.success(
                reviews.list(nettingId).stream().map(NettingReviewWebResponse::from).toList());
    }

    @PostMapping("/{reviewId}/decisions")
    public ApiResponse<NettingReviewWebResponse> decide(
            @PathVariable UUID reviewId,
            @Valid @RequestBody ResolveNettingReviewWebRequest request) {
        return ApiResponse.success(
                NettingReviewWebResponse.from(reviews.resolve(reviewId, request.toCommand())));
    }
}
