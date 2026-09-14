package com.amateur.howmuchbackend.application.port.in.user;

import com.amateur.howmuchbackend.application.port.in.user.command.DeleteMyAccountCommand;

public interface DeleteMyAccountUseCase {
    void delete(DeleteMyAccountCommand command);
}
