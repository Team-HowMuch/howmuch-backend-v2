package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.command.ReplaceAccountDisplayOrdersCommand;

public interface ReplaceAccountDisplayOrdersUseCase {
    void replaceDisplayOrders(ReplaceAccountDisplayOrdersCommand command);
}
