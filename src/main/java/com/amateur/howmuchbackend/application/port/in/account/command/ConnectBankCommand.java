package com.amateur.howmuchbackend.application.port.in.account.command;

public record ConnectBankCommand(String organization, String loginId, String password, String birthDate) {
}
