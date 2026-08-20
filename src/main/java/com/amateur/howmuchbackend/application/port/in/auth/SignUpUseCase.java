package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.command.SignUpCommand;

public interface SignUpUseCase {
    void signUp(SignUpCommand command);
}
