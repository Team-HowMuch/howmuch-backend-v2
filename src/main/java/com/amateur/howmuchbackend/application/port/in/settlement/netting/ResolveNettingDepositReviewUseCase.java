package com.amateur.howmuchbackend.application.port.in.settlement.netting;

import com.amateur.howmuchbackend.application.port.in.settlement.netting.command.ResolveNettingReviewCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.netting.result.NettingReviewResult;

import java.util.*;

public interface ResolveNettingDepositReviewUseCase {
    List<NettingReviewResult> list(UUID nettingId);

    NettingReviewResult resolve(UUID reviewId, ResolveNettingReviewCommand command);
}
