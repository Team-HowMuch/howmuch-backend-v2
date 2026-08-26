package com.amateur.howmuchbackend.application.port.in.treasurer;

import com.amateur.howmuchbackend.application.port.in.treasurer.request.AskTreasurerRequest;
import com.amateur.howmuchbackend.application.port.in.treasurer.response.TreasurerAnswerResponse;

public interface AskTreasurerUseCase {
    TreasurerAnswerResponse ask(AskTreasurerRequest request);
}
