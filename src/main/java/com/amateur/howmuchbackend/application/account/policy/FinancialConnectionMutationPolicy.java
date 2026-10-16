package com.amateur.howmuchbackend.application.account.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;

import org.springframework.stereotype.Component;

@Component
public class FinancialConnectionMutationPolicy {
    public void requireNoReference(boolean pending) {
        if (pending)
            throw ApplicationException.of(
                    ExternalConnectionStatusCode.CERTIFICATE_REFERENCE_IN_PROGRESS);
    }

    public void requireAvailable(boolean reference, boolean certificate) {
        requireNoReference(reference || certificate);
    }
}
