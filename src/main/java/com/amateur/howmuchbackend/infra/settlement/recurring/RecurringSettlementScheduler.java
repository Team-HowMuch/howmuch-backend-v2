package com.amateur.howmuchbackend.infra.settlement.recurring;

import com.amateur.howmuchbackend.application.port.in.settlement.DispatchRecurringSettlementsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;

/** 기본 1분 간격으로 도래 회차 탐색을 실행한다. 반복 기능이 켜져 있을 때만 빈으로 등록된다. */
@RequiredArgsConstructor
public class RecurringSettlementScheduler {
    private final DispatchRecurringSettlementsUseCase dispatcher;
    private final RecurringSettlementProperties properties;

    @Scheduled(scheduler = "recurringSettlementScheduler",
            fixedDelayString = "${howmuch.settlement.recurring.poll-interval:1m}",
            initialDelayString = "${howmuch.settlement.recurring.poll-interval:1m}")
    public void dispatch() {
        dispatcher.dispatch(properties.batchSize());
    }
}
