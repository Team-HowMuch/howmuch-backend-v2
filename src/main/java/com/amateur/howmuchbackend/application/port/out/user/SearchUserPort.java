package com.amateur.howmuchbackend.application.port.out.user;

import com.amateur.howmuchbackend.domain.user.User;

import java.util.Collection;
import java.util.List;

public interface SearchUserPort {
    List<User> searchActiveByPublicIdPrefix(
            String publicIdPrefix,
            Collection<Long> excludedUserIds,
            int limit
    );
}
