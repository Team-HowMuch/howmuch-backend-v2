package com.amateur.howmuchbackend.application.port.out.user;

import java.time.LocalDateTime;

public interface UserPushTargetStorePort {
    boolean claim(Long userId, String firebaseInstallationId, LocalDateTime now);

    boolean clear(Long userId, LocalDateTime now);
}
