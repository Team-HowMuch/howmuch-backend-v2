package com.amateur.howmuchbackend.application.port.out.analytics;

import java.time.LocalDate;

public interface FetchAnalyticsPort {
    AnalyticsSnapshot fetch(LocalDate from, LocalDate to);
}
