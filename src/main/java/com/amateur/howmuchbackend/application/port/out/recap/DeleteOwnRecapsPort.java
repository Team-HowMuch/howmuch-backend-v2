package com.amateur.howmuchbackend.application.port.out.recap;

import java.time.LocalDateTime;

public interface DeleteOwnRecapsPort {
    /** Delete only this owner's snapshots/jobs and durably queue their reserved/copied files in the caller transaction. */
    void deleteOwnedRecaps(Long ownerId, LocalDateTime now);
}
