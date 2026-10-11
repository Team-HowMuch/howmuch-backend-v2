package com.amateur.howmuchbackend.application.port.in.certificate;

import com.amateur.howmuchbackend.application.port.in.certificate.command.*;
import com.amateur.howmuchbackend.application.port.in.certificate.result.*;

public interface CancelCertificateRegistrationUseCase {
    void cancel(java.util.UUID id, String pcCapability);
}
