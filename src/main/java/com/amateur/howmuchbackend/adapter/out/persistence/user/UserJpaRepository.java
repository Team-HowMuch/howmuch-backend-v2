package com.amateur.howmuchbackend.adapter.out.persistence.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {
    Optional<UserJpaEntity> findByPhoneNumber(String phoneNumber);

    Optional<UserJpaEntity> findByIdentityKey(String identityKey);

    Optional<UserJpaEntity> findByPublicId(String publicId);

    boolean existsByPublicId(String publicId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserJpaEntity user where user.id = :id")
    Optional<UserJpaEntity> findByIdForUpdate(@Param("id") Long id);
}
