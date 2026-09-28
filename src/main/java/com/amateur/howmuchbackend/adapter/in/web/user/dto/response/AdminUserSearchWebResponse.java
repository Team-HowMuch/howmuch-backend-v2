package com.amateur.howmuchbackend.adapter.in.web.user.dto.response;

import com.amateur.howmuchbackend.application.port.in.user.result.AdminUserSearchResult;

public record AdminUserSearchWebResponse(Long userId, String name) {
    public static AdminUserSearchWebResponse from(AdminUserSearchResult result) {
        return new AdminUserSearchWebResponse(result.userId(), result.name());
    }
}
