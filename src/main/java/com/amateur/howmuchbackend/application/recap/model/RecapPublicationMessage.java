package com.amateur.howmuchbackend.application.recap.model;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.UUID;

public record RecapPublicationMessage(UUID messageId, Long userId, YearMonth yearMonth,
                                     int completedSettlementCount, LocalDateTime occurredAt) {}
