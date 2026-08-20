package com.amateur.howmuchbackend.application.port.in.auth;

import com.amateur.howmuchbackend.application.port.in.auth.command.RefreshTokenCommand;
import com.amateur.howmuchbackend.application.port.in.auth.result.TokenResult;

public interface RefreshTokenUseCase {
    TokenResult refresh(RefreshTokenCommand command);
}
