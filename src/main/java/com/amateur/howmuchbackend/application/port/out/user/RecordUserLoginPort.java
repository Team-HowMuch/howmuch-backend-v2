package com.amateur.howmuchbackend.application.port.out.user;

import java.time.LocalDateTime;

public interface RecordUserLoginPort {
    /** Records login timestamps without replacing other user state. */
    void recordLogin(Long userId, LocalDateTime now);
}
