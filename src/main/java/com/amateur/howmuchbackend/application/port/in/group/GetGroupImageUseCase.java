package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.group.model.GroupImage;

public interface GetGroupImageUseCase {
    GroupImage getImage(Long groupId);
}
