package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class NettingBankContextReader {
    private final LoadAccountPort accounts;
    private final ExternalConnectionStorePort connections;

    @Transactional
    public ExternalConnection load(long accountId, long userId, String expectedNumber) {
        var account =
                accounts.findActiveByIdAndUserId(accountId, userId)
                        .orElseThrow(
                                () ->
                                        ApplicationException.of(
                                                ExternalConnectionStatusCode
                                                        .BANK_ACCOUNT_RECONNECT_REQUIRED));
        if (account.externalAccountKey() == null
                || expectedNumber == null
                || !account.externalAccountKey().strip().equals(expectedNumber.strip()))
            throw ApplicationException.of(
                    ExternalConnectionStatusCode.BANK_ACCOUNT_RECONNECT_REQUIRED);
        return connections
                .findActiveByIdAndUserId(account.externalConnectionId(), userId)
                .orElseThrow(
                        () ->
                                ApplicationException.of(
                                        ExternalConnectionStatusCode
                                                .BANK_ACCOUNT_RECONNECT_REQUIRED));
    }
}
