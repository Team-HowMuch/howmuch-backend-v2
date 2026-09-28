package com.amateur.howmuchbackend.adapter.out.security;

import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

final class BankAccountCredentialCodec {
    private BankAccountCredentialCodec() {
    }

    static byte[] encode(BankAccountCredentials credentials) {
        byte[] number = credentials.number().getBytes(StandardCharsets.UTF_8);
        byte[] password = credentials.password().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(Integer.BYTES + number.length + password.length)
                .putInt(number.length).put(number).put(password).array();
    }

    static BankAccountCredentials decode(byte[] plaintext) {
        if (plaintext.length < Integer.BYTES) {
            throw invalid();
        }
        ByteBuffer buffer = ByteBuffer.wrap(plaintext);
        int numberLength = buffer.getInt();
        if (numberLength < 1 || numberLength >= buffer.remaining()) {
            throw invalid();
        }
        byte[] number = new byte[numberLength];
        buffer.get(number);
        byte[] password = new byte[buffer.remaining()];
        buffer.get(password);
        return new BankAccountCredentials(
                new String(number, StandardCharsets.UTF_8), new String(password, StandardCharsets.UTF_8)
        );
    }

    private static ApplicationException invalid() {
        return ApplicationException.of(ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_STORAGE_INVALID);
    }
}
