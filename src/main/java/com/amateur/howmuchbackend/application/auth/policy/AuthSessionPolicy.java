package com.amateur.howmuchbackend.application.auth.policy;

public record AuthSessionPolicy(
        boolean singleSessionPerUser,
        boolean rotateRequestToken
) {
}
