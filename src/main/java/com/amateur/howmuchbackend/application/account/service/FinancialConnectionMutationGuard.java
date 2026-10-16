package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.policy.FinancialConnectionMutationPolicy;
import com.amateur.howmuchbackend.application.port.out.account.FinancialConnectionMutationStatePort;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FinancialConnectionMutationGuard {
    private final FinancialConnectionMutationStatePort state;
    private final FinancialConnectionMutationPolicy policy;

    public void requireNoReference(long userId) {
        policy.requireNoReference(state.hasActiveReference(userId));
    }

    public void requireAvailable(long userId) {
        policy.requireAvailable(
                state.hasActiveReference(userId), state.hasUnresolvedCertificate(userId));
    }
}
