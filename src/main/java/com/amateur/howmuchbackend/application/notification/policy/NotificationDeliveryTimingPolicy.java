package com.amateur.howmuchbackend.application.notification.policy;

import org.springframework.stereotype.Component;
import java.time.*;

@Component
public class NotificationDeliveryTimingPolicy {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public LocalDateTime resumeAutomaticAt(LocalDateTime now) {
        var korean = now.atOffset(ZoneOffset.UTC).atZoneSameInstant(SEOUL);
        if (korean.getHour() < 3 || korean.getHour() >= 6) return null;
        return LocalDateTime.ofInstant(korean.toLocalDate().atTime(6, 0).atZone(SEOUL).toInstant(), ZoneOffset.UTC);
    }
}
