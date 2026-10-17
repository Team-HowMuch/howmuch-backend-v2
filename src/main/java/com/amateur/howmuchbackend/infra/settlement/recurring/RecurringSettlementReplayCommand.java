package com.amateur.howmuchbackend.infra.settlement.recurring;

import com.amateur.howmuchbackend.application.port.in.settlement.ReplayRecurringSettlementUseCase;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 운영자 전용 프로세스 실행 명령. 공개 HTTP 복구 endpoint가 아니다. 정확한 반복 ID와 원래 회차의
 * UTC 예정 시각을 입력하고, 완료 후 프로세스 컨텍스트를 닫는다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "howmuch.settlement.recurring", name = {"enabled", "replay.enabled"},
        havingValue = "true")
public class RecurringSettlementReplayCommand implements ApplicationRunner {
    private final ReplayRecurringSettlementUseCase replay;
    private final ConfigurableApplicationContext context;

    @Override
    public void run(ApplicationArguments arguments) {
        UUID id = UUID.fromString(required(arguments, "recurring-replay-id"));
        LocalDateTime scheduledFor = LocalDateTime.ofInstant(
                Instant.parse(required(arguments, "recurring-replay-scheduled-for")), ZoneOffset.UTC);
        boolean confirmWorkerStopped = arguments.containsOption("recurring-replay-confirm-worker-stopped")
                && "true".equals(required(arguments, "recurring-replay-confirm-worker-stopped"));
        replay.replay(id, scheduledFor, confirmWorkerStopped);
        context.close();
    }

    private String required(ApplicationArguments arguments, String name) {
        var values = arguments.getOptionValues(name);
        if (values == null || values.size() != 1 || values.getFirst().isBlank()) {
            throw new IllegalArgumentException("Missing or repeated replay argument: " + name);
        }
        return values.getFirst();
    }
}
