package com.amateur.howmuchbackend.application.port.in.user;

import com.amateur.howmuchbackend.application.port.in.user.command.SearchAdminUserCommand;
import com.amateur.howmuchbackend.application.port.in.user.result.AdminUserSearchResult;

public interface SearchAdminUserUseCase {
    AdminUserSearchResult search(SearchAdminUserCommand command);
}
