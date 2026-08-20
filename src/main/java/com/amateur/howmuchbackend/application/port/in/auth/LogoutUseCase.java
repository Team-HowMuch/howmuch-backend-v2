package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.command.LogoutCommand;

public interface LogoutUseCase {
    void logout(LogoutCommand command);
}
