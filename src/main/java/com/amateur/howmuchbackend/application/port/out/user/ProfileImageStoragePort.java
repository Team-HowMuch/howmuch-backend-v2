package com.amateur.howmuchbackend.application.port.out.user;

import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import com.amateur.howmuchbackend.application.user.model.ProfileImageUpload;

import java.util.Optional;

public interface ProfileImageStoragePort {
    String save(ProfileImageUpload image);

    Optional<ProfileImage> load(String fileName);

    void delete(String fileName);
}
