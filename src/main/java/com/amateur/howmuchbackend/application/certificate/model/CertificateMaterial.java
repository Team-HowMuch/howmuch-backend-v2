package com.amateur.howmuchbackend.application.certificate.model;

import java.util.Arrays;

public final class CertificateMaterial implements AutoCloseable {
    private final byte[] der;
    private final byte[] key;
    private final String password, accountNumber, accountPassword, birthDate;

    public CertificateMaterial(
            byte[] der,
            byte[] key,
            String password,
            String accountNumber,
            String accountPassword,
            String birthDate) {
        this.der = der.clone();
        this.key = key.clone();
        this.password = password;
        this.accountNumber = accountNumber;
        this.accountPassword = accountPassword;
        this.birthDate = birthDate;
    }

    public byte[] der() {
        return der.clone();
    }

    public byte[] key() {
        return key.clone();
    }

    public String password() {
        return password;
    }

    public String accountNumber() {
        return accountNumber;
    }

    public String accountPassword() {
        return accountPassword;
    }

    public String birthDate() {
        return birthDate;
    }

    public void close() {
        Arrays.fill(der, (byte) 0);
        Arrays.fill(key, (byte) 0);
    }

    @Override
    public String toString() {
        return "CertificateMaterial[REDACTED]";
    }
}
