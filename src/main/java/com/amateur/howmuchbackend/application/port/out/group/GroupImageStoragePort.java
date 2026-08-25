package com.amateur.howmuchbackend.application.port.out.group;

import com.amateur.howmuchbackend.application.group.model.GroupImage;
import com.amateur.howmuchbackend.application.group.model.GroupImageUpload;

import java.util.Optional;

public interface GroupImageStoragePort {
    String save(GroupImageUpload image);

    Optional<GroupImage> load(String fileName);

    void delete(String fileName);
}
