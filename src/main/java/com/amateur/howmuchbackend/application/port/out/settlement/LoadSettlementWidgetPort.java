package com.amateur.howmuchbackend.application.port.out.settlement;

import java.util.List;

public interface LoadSettlementWidgetPort {
    List<SettlementWidgetReadModel> findAllInProgress(Long userId);
}
