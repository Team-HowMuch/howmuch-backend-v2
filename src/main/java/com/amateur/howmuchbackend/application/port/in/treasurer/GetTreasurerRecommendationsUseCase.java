package com.amateur.howmuchbackend.application.port.in.treasurer;

import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerRecommendationsResponse;

public interface GetTreasurerRecommendationsUseCase {
    TreasurerRecommendationsResponse getRecommendations();
}
