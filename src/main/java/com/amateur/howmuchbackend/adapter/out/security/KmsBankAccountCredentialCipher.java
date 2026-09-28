package com.amateur.howmuchbackend.adapter.out.security;

import com.amateur.howmuchbackend.application.account.model.BankAccountCredentials;
import com.amateur.howmuchbackend.application.account.model.TemporaryKmsUnavailableException;
import com.amateur.howmuchbackend.application.port.out.external.KmsAccountCredentialCipherPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.KmsException;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;

@Component
public class KmsBankAccountCredentialCipher implements KmsAccountCredentialCipherPort {
    private static final String VERSION = "kms:v1:";
    private final ObjectProvider<KmsClient> client;
    private final String keyId;

    public KmsBankAccountCredentialCipher(
            ObjectProvider<KmsClient> client,
            @Value("${ACCOUNT_CREDENTIAL_KMS_KEY_ID:}") String keyId
    ) {
        this.client = client;
        this.keyId = keyId;
    }

    @Override
    public boolean enabled() {
        return StringUtils.hasText(keyId);
    }

    @Override
    public String encrypt(Long userId, String organization, BankAccountCredentials credentials) {
        try {
            byte[] plaintext = BankAccountCredentialCodec.encode(credentials);
            return VERSION + Base64.getEncoder().encodeToString(client.getObject().encrypt(EncryptRequest.builder()
                    .keyId(keyId)
                    .plaintext(SdkBytes.fromByteArray(plaintext))
                    .encryptionContext(context(userId, organization))
                    .build()).ciphertextBlob().asByteArray());
        } catch (KmsException | SdkClientException exception) {
            throw mapped(exception);
        }
    }

    @Override
    public BankAccountCredentials decrypt(Long userId, String organization, String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(VERSION)) {
            throw storageInvalid();
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(ciphertext.substring(VERSION.length()));
            return BankAccountCredentialCodec.decode(client.getObject().decrypt(DecryptRequest.builder()
                    .keyId(keyId)
                    .ciphertextBlob(SdkBytes.fromByteArray(bytes))
                    .encryptionContext(context(userId, organization))
                    .build()).plaintext().asByteArray());
        } catch (KmsException | SdkClientException exception) {
            throw mapped(exception);
        } catch (IllegalArgumentException exception) {
            throw storageInvalid();
        }
    }

    private Map<String, String> context(Long userId, String organization) {
        return Map.of("purpose", "bank-account-login", "userId", userId.toString(), "organization", organization);
    }

    private RuntimeException mapped(RuntimeException exception) {
        if (exception instanceof KmsException kms
                && (kms.statusCode() >= 500 || kms.isThrottlingException())) {
            return new TemporaryKmsUnavailableException(exception);
        }
        if (exception instanceof ApiCallTimeoutException || exception instanceof ApiCallAttemptTimeoutException) {
            return new TemporaryKmsUnavailableException(exception);
        }
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SSLException) {
                return storageInvalid();
            }
            if (cause instanceof IOException) {
                return new TemporaryKmsUnavailableException(exception);
            }
        }
        return ApplicationException.of(ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_STORAGE_INVALID);
    }

    private ApplicationException storageInvalid() {
        return ApplicationException.of(ExternalConnectionStatusCode.BANK_ACCOUNT_CREDENTIAL_STORAGE_INVALID);
    }
}
