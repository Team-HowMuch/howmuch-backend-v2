package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.certificate.model.*;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateCommand;
import com.amateur.howmuchbackend.application.port.in.certificate.command.ReceiveCertificateResultCommand;

public interface ReceiveCertificateMessagesUseCase {
    boolean receiveCommand(ReceiveCertificateCommand command, String hash);

    boolean receiveResult(ReceiveCertificateResultCommand event, String hash);
}
