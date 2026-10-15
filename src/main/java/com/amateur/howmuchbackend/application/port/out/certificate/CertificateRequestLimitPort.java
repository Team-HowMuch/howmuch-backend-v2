package com.amateur.howmuchbackend.application.port.out.certificate;

public interface CertificateRequestLimitPort {
    boolean acquire(String hashedAddress);
}
