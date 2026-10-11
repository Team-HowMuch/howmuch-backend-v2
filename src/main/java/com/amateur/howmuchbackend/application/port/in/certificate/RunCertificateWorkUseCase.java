package com.amateur.howmuchbackend.application.port.in.certificate;

public interface RunCertificateWorkUseCase {
    void executeOne();

    void publishOne(boolean result);

    void recover();
}
