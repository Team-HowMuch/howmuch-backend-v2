package com.amateur.howmuchbackend.application.account.model;

public record EncryptedBankAccountCredentials(String localCiphertext, String kmsCiphertext) {
    @Override
    public String toString() {
        return "EncryptedBankAccountCredentials[localCiphertext=***, kmsCiphertext=***]";
    }
}
