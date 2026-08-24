package com.amateur.howmuchbackend.application.port.out.auth;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;

import java.util.Optional;

public interface CurrentActorPort {
    CurrentActor getCurrentActor();

    Optional<CurrentActor> findCurrentActor();
}
