package com.amateur.howmuchbackend.application.port.out.recap;

import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface RecapImageStoragePort {
    void verifyReady();
    void copyProfile(String sourceFileName, String reservedFileName);
    Optional<ProfileImage> load(String fileName);
    void delete(String fileName);
    /** Reads the next bounded directory batch; successive calls cycle through every entry. */
    List<String> findOlderThan(LocalDateTime cutoff);
}
