package com.amateur.howmuchbackend.application.port.in.external;

import com.amateur.howmuchbackend.application.port.in.external.result.AdditionalAuthenticationResult;

import java.util.List;

public interface ListAdditionalAuthenticationsUseCase {
    List<AdditionalAuthenticationResult> list();
}
