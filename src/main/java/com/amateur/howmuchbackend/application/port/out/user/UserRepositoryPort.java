package com.amateur.howmuchbackend.application.port.out.user;

import com.amateur.howmuchbackend.domain.user.User;

import java.util.Optional;

public interface UserRepositoryPort {
    Optional<User> findById(Long id);

    Optional<User> findByIdentityKey(String identityKey);

    User save(User user);

    User update(User user);
}
