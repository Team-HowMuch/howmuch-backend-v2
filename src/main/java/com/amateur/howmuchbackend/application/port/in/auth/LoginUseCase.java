package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.command.LoginCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.TokenResult;

public interface LoginUseCase {
    TokenResult login(LoginCommand command);
}
