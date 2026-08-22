package com.amateur.howmuchbackend.application.port.out.user;

import com.amateur.howmuchbackend.domain.user.User;

import java.util.Optional;

public interface LoadUserPort {
    Optional<User> findById(Long id);

    Optional<User> findByIdForUpdate(Long id);

    Optional<User> findByPhoneNumber(String phoneNumber);

    boolean existsByPublicId(String publicId);
}
