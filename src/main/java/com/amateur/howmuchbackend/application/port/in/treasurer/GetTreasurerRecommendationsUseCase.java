package com.amateur.howmuchbackend.application.port.in.treasurer;

import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerRecommendationsResult;

public interface GetTreasurerRecommendationsUseCase {
    TreasurerRecommendationsResult getRecommendations();
}
