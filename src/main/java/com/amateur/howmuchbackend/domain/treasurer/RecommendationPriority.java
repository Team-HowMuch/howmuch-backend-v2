package com.amateur.howmuchbackend.domain.treasurer;

public enum RecommendationPriority {
    SEND_OVERDUE,
    SEND_STALE,
    SEND_OPEN,
    RECEIVE_OVERDUE,
    RECEIVE_STALE,
    RECEIVE_OPEN
}
