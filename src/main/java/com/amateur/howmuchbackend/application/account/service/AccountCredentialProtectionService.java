package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.account.model.EncryptedBankAccountCredentials;
import com.amateur.howmuchbackend.application.account.model.TemporaryKmsUnavailableException;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.external.KmsAccountCredentialCipherPort;
import com.amateur.howmuchbackend.application.port.out.external.LocalAccountCredentialCipherPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.ConnectedInstitution;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountCredentialProtectionService {
    private final LocalAccountCredentialCipherPort localCipher;
    private final KmsAccountCredentialCipherPort kmsCipher;
    private final ExternalConnectionStorePort connectionStorePort;

    public EncryptedBankAccountCredentials protect(
            Long userId, String organization, BankAccountCredentials credentials
    ) {
        String local = localCipher.encrypt(userId, organization, credentials);
        String kms = null;
        if (kmsCipher.enabled()) {
            try {
                kms = kmsCipher.encrypt(userId, organization, credentials);
            } catch (TemporaryKmsUnavailableException exception) {
                log.warn("KMS encryption unavailable; local bank credential protection remains active");
            }
        }
        return new EncryptedBankAccountCredentials(local, kms);
    }

    public BankAccountCredentials reveal(Long userId, Long connectionId, String organization) {
        ConnectedInstitution institution = connectionStorePort.findInstitution(connectionId, organization)
                .orElseThrow(() -> ApplicationException.of(
                        ExternalConnectionStatusCode.BANK_ACCOUNT_RECONNECT_REQUIRED
                ));
        String local = institution.localCredentialsCiphertext();
        if (local == null) {
            throw ApplicationException.of(ExternalConnectionStatusCode.BANK_ACCOUNT_RECONNECT_REQUIRED);
        }
        if (kmsCipher.enabled() && institution.kmsCredentialsCiphertext() != null) {
            try {
                return kmsCipher.decrypt(userId, organization, institution.kmsCredentialsCiphertext());
            } catch (TemporaryKmsUnavailableException exception) {
                log.warn("KMS decryption unavailable; local bank credential fallback active");
            }
        }
        BankAccountCredentials credentials = localCipher.decrypt(userId, organization, local);
        if (kmsCipher.enabled() && institution.kmsCredentialsCiphertext() == null) {
            try {
                connectionStorePort.saveKmsCredentialsIfAbsent(
                        connectionId, organization, kmsCipher.encrypt(userId, organization, credentials)
                );
            } catch (TemporaryKmsUnavailableException exception) {
                log.warn("KMS credential backfill deferred");
            }
        }
        return credentials;
    }
}
