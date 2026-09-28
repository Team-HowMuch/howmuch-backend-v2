package com.amateur.howmuchbackend.application.port.out.external;

import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;

public interface KmsAccountCredentialCipherPort {
    boolean enabled();

    String encrypt(Long userId, String organization, BankAccountCredentials credentials);

    BankAccountCredentials decrypt(Long userId, String organization, String ciphertext);
}
