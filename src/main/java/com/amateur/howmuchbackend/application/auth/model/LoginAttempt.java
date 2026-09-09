package com.amateur.howmuchbackend.application.auth.model;

public record LoginAttempt(String generation, int attemptCount) {
}
