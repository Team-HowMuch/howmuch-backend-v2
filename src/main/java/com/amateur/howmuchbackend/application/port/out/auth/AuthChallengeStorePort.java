package com.amateur.howmuchbackend.application.port.out.auth;

import com.amateur.howmuchbackend.domain.auth.challenge.AuthChallenge;

import java.time.Duration;
import java.util.Optional;

public interface AuthChallengeStorePort {
    Optional<AuthChallenge> findById(String challengeId);

    void save(AuthChallenge challenge, Duration ttl);

    void deleteById(String challengeId);
}
