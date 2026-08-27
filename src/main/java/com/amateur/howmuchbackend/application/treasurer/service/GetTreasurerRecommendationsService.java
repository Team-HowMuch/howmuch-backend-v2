package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.port.in.treasurer.GetTreasurerRecommendationsUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerRecommendationsResult;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerActionType;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerRecommendation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

@Service
@RequiredArgsConstructor
public class GetTreasurerRecommendationsService implements GetTreasurerRecommendationsUseCase {
    private final TreasurerSettlementQuery treasurerSettlementQuery;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public TreasurerRecommendationsResult getRecommendations() {
        List<TreasurerRecommendation> recommendations = TreasurerRecommendation.prioritize(
                treasurerSettlementQuery.loadMine(),
                clock.instant()
        );
        if (recommendations.isEmpty()) {
            return new TreasurerRecommendationsResult("처리할 정산이 없어요.", List.of());
        }
        return new TreasurerRecommendationsResult(
                null,
                recommendations.stream()
                        .map(this::toItem)
                        .toList()
        );
    }

    private TreasurerRecommendationsResult.Item toItem(TreasurerRecommendation recommendation) {
        return new TreasurerRecommendationsResult.Item(
                recommendation.settlement().settlementId(),
                recommendation.settlement().title(),
                recommendation.settlement().direction().name(),
                recommendation.priority().name(),
                recommendation.reason(),
                recommendation.settlement().amountOrZero(),
                recommendation.actions().stream()
                        .map(action -> new TreasurerRecommendationsResult.Action(action.name(), labelOf(action)))
                        .toList()
        );
    }

    private String labelOf(TreasurerActionType action) {
        return switch (action) {
            case SETTLEMENT_DETAIL -> "정산 상세 보기";
            case CHECK_DEPOSIT -> "입금 확인";
            case TRANSFER -> "송금하기";
        };
    }
}
