package com.amateur.howmuchbackend.adapter.out.persistence.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserJpaEntity, Long> {
    Optional<UserJpaEntity> findByPhoneNumber(String phoneNumber);

    Optional<UserJpaEntity> findByIdentityKey(String identityKey);

    Optional<UserJpaEntity> findByPublicId(String publicId);

    boolean existsByPublicId(String publicId);

    List<UserJpaEntity> findByActiveTrueAndPublicIdStartingWithOrderByPublicIdAsc(
            String publicIdPrefix,
            Pageable pageable
    );

    List<UserJpaEntity> findByActiveTrueAndPublicIdStartingWithAndIdNotInOrderByPublicIdAsc(
            String publicIdPrefix,
            Collection<Long> excludedUserIds,
            Pageable pageable
    );

    List<UserJpaEntity> findAllByActiveTrueAndIdentityKeyIn(Collection<String> identityKeys);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserJpaEntity user where user.id = :id")
    Optional<UserJpaEntity> findByIdForUpdate(@Param("id") Long id);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserJpaEntity user
            set user.lastLoginAt = :now,
                user.updatedAt = :now
            where user.id = :userId
            """)
    int recordLogin(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserJpaEntity user
            set user.fcmToken = null,
                user.updatedAt = :now
            where user.fcmToken = :firebaseInstallationId
              and user.id <> :userId
            """)
    int releaseFcmToken(
            @Param("userId") Long userId,
            @Param("firebaseInstallationId") String firebaseInstallationId,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserJpaEntity user
            set user.fcmToken = :firebaseInstallationId,
                user.updatedAt = :now
            where user.id = :userId
            """)
    int assignFcmToken(
            @Param("userId") Long userId,
            @Param("firebaseInstallationId") String firebaseInstallationId,
            @Param("now") LocalDateTime now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update UserJpaEntity user
            set user.fcmToken = null,
                user.updatedAt = :now
            where user.id = :userId
            """)
    int clearFcmToken(
            @Param("userId") Long userId,
            @Param("now") LocalDateTime now
    );
}
