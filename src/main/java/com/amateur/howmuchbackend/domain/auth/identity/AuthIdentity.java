package com.amateur.howmuchbackend.domain.auth.identity;

import com.amateur.howmuchbackend.domain.user.Gender;

import java.time.LocalDate;
import java.util.Objects;

public record AuthIdentity(
        String identityKey,
        String name,
        LocalDate birthDate,
        Gender gender,
        String phoneNumber
) {
    public AuthIdentity {
        Objects.requireNonNull(identityKey);
        Objects.requireNonNull(name);
        Objects.requireNonNull(birthDate);
        Objects.requireNonNull(gender);
        Objects.requireNonNull(phoneNumber);
    }
}
