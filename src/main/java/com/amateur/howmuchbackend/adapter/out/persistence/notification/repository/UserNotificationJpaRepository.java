package com.amateur.howmuchbackend.adapter.out.persistence.notification.repository;

import com.amateur.howmuchbackend.adapter.out.persistence.notification.entity.UserNotificationJpaEntity;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.*;

public interface UserNotificationJpaRepository extends JpaRepository<UserNotificationJpaEntity, UUID> {
    @Query(value = "SELECT * FROM user_notifications WHERE recipient_user_id=:userId ORDER BY created_at DESC,id DESC LIMIT :limit", nativeQuery = true)
    List<UserNotificationJpaEntity> firstPage(@Param("userId") Long userId, @Param("limit") int limit);
    @Query(value = "SELECT * FROM user_notifications WHERE recipient_user_id=:userId AND (created_at,id)<(:beforeAt,:beforeId) ORDER BY created_at DESC,id DESC LIMIT :limit", nativeQuery = true)
    List<UserNotificationJpaEntity> before(@Param("userId") Long userId, @Param("beforeAt") LocalDateTime beforeAt,
            @Param("beforeId") UUID beforeId, @Param("limit") int limit);
    long countByRecipientUserIdAndReadAtIsNull(Long userId);
    Optional<UserNotificationJpaEntity> findByIdAndRecipientUserId(UUID id, Long userId);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE user_notifications SET read_at=COALESCE(read_at,:now),
                updated_at=CASE WHEN read_at IS NULL THEN :now ELSE updated_at END
            WHERE id=:id AND recipient_user_id=:userId
            """, nativeQuery = true)
    int markRead(@Param("id") UUID id, @Param("userId") Long userId, @Param("now") LocalDateTime now);
}
