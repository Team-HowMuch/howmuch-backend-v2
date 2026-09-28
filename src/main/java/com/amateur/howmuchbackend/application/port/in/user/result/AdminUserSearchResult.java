package com.amateur.howmuchbackend.application.port.in.user.result;

import com.amateur.howmuchbackend.domain.user.User;

public record AdminUserSearchResult(Long userId, String name) {
    public static AdminUserSearchResult from(User user) {
        return new AdminUserSearchResult(user.id(), user.name());
    }
}
