package com.amateur.howmuchbackend.application.account.model;

public record BankAccountCredentials(String number, String password) {
    @Override
    public String toString() {
        return "BankAccountCredentials[number=***, password=***]";
    }
}
