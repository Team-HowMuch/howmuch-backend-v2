package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerRecommendationsUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerRecommendationsResponse;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerActionType;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerRecommendation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GetTreasurerRecommendationsService implements GetTreasurerRecommendationsUseCase {
    private final TreasurerSettlementQuery treasurerSettlementQuery;
    private final Clock clock;

    @Override
    public TreasurerRecommendationsResponse getRecommendations() {
        List<TreasurerRecommendation> recommendations = TreasurerRecommendation.prioritize(
                treasurerSettlementQuery.loadMine(),
                clock.instant()
        );
        if (recommendations.isEmpty()) {
            return new TreasurerRecommendationsResponse("처리할 정산이 없어요.", List.of());
        }
        return new TreasurerRecommendationsResponse(
                null,
                recommendations.stream()
                        .map(this::toItem)
                        .toList()
        );
    }

    private TreasurerRecommendationsResponse.Item toItem(TreasurerRecommendation recommendation) {
        return new TreasurerRecommendationsResponse.Item(
                recommendation.settlement().settlementId(),
                recommendation.settlement().title(),
                recommendation.settlement().direction().name(),
                recommendation.priority().name(),
                recommendation.reason(),
                recommendation.settlement().amountOrZero(),
                recommendation.actions().stream()
                        .map(action -> new TreasurerRecommendationsResponse.Action(action.name(), labelOf(action)))
                        .toList()
        );
    }

    private String labelOf(TreasurerActionType action) {
        return switch (action) {
            case SETTLEMENT_DETAIL -> "정산 상세 보기";
            case CHECK_DEPOSIT -> "입금 확인";
            case TRANSFER -> "송금하기";
            case SEND_NOTIFICATION -> "알림 보내기";
        };
    }
}
