package com.amateur.howmuchbackend.application.port.in.account.command;

public record UpdateAccountDisplayOrderCommand(
        Long accountId,
        int displayOrder
) {
}
