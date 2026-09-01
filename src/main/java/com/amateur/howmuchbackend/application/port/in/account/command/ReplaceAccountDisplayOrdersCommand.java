package com.amateur.howmuchbackend.application.port.in.account.command;

import java.util.List;

public record ReplaceAccountDisplayOrdersCommand(
        List<Long> accountIds
) {
}
