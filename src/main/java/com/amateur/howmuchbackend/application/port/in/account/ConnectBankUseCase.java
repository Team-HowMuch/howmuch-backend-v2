package com.amateur.howmuchbackend.application.port.in.account;

import com.amateur.howmuchbackend.application.port.in.account.command.ConnectBankCommand;
import com.amateur.howmuchbackend.application.port.in.account.result.BankConnectionResult;

public interface ConnectBankUseCase {
    BankConnectionResult connect(ConnectBankCommand command);
}
