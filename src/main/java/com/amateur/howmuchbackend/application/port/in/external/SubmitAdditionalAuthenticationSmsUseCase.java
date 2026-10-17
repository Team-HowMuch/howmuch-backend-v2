package com.amateur.howmuchbackend.application.port.in.external;

import com.amateur.howmuchbackend.application.port.in.external.command.SubmitAdditionalAuthenticationSmsCommand;
import com.amateur.howmuchbackend.application.port.in.external.result.AdditionalAuthenticationResult;

public interface SubmitAdditionalAuthenticationSmsUseCase {
    AdditionalAuthenticationResult submit(SubmitAdditionalAuthenticationSmsCommand command);
}
