package com.amateur.howmuchbackend.application.port.out.account;

public interface FinancialConnectionMutationStatePort {
    boolean hasActiveReference(long userId);

    boolean hasUnresolvedCertificate(long userId);
}
