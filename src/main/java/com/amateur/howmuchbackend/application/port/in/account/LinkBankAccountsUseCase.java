package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.command.LinkBankAccountsCommand;
import com.amateur.howmuchbackend.application.port.in.account.result.LinkedAccountsResult;

public interface LinkBankAccountsUseCase {
    LinkedAccountsResult link(LinkBankAccountsCommand command);
}
