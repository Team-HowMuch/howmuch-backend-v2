package com.amateur.howmuchbackend.application.port.out.group;

import com.amateur.howmuchbackend.domain.group.Group;

public interface SaveGroupPort {
    Group save(Group group);

    Group update(Group group);
}
