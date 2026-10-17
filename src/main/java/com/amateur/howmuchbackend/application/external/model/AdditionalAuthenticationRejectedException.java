package com.amateur.howmuchbackend.application.external.model;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.StatusCode;

public class AdditionalAuthenticationRejectedException extends ApplicationException {
    public AdditionalAuthenticationRejectedException(StatusCode statusCode) {
        super(statusCode);
    }
}
