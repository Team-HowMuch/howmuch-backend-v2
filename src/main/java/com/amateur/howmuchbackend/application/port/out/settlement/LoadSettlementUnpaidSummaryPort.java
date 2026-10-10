package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.List;

public interface LoadSettlementUnpaidSummaryPort {
    List<SettlementUnpaidSummaryReadModel> findAllByCreatorId(Long creatorId);
}
