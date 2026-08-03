package com.amateur.howmuchbackend.application.port.out.auth;

public interface SmsSenderPort {
    void send(String phoneNumber, String verificationCode);
}
