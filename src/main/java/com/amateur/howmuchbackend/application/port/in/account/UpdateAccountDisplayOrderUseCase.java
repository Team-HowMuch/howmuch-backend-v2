package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.command.UpdateAccountDisplayOrderCommand;

public interface UpdateAccountDisplayOrderUseCase {
    void updateDisplayOrder(UpdateAccountDisplayOrderCommand command);
}
