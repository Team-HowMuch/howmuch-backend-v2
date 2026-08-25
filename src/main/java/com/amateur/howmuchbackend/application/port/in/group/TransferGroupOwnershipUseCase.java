package com.amateur.howmuchbackend.application.port.in.group;

import com.amateur.howmuchbackend.application.port.in.group.command.TransferGroupOwnershipCommand;

public interface TransferGroupOwnershipUseCase {
    void transfer(Long groupId, TransferGroupOwnershipCommand command);
}
