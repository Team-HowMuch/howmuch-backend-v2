package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.account.policy.AccountDisplayOrderPolicy;
import com.amateur.howmuchbackend.application.port.in.account.SetDefaultAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.account.UpdateAccountDisplayOrderUseCase;
import com.amateur.howmuchbackend.application.port.in.account.command.UpdateAccountDisplayOrderCommand;
import com.amateur.howmuchbackend.application.port.out.account.AccountPreferenceStorePort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountPreferenceService implements
        SetDefaultAccountUseCase,
        UpdateAccountDisplayOrderUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadAccountPort loadAccountPort;
    private final AccountPreferenceStorePort accountPreferenceStorePort;
    private final AccountDisplayOrderPolicy accountDisplayOrderPolicy;

    @Override
    @Transactional
    public void setDefault(Long accountId) {
        Long userId = currentActorPort.getCurrentActor().userId();
        List<Account> accounts = loadAccountPort.findActiveByUserIdForUpdate(userId);
        requireOwnedAccount(accounts, accountId);
        accountPreferenceStorePort.setDefault(userId, accountId);
    }

    @Override
    @Transactional
    public void updateDisplayOrder(UpdateAccountDisplayOrderCommand command) {
        if (command == null) {
            throw ApplicationException.of(AccountStatusCode.INVALID_ACCOUNT_DISPLAY_ORDER);
        }
        Long userId = currentActorPort.getCurrentActor().userId();
        List<Account> accounts = loadAccountPort.findActiveByUserIdForUpdate(userId);
        List<Long> orderedIds = accountDisplayOrderPolicy.reorder(
                accounts,
                command.accountId(),
                command.displayOrder()
        );
        accountPreferenceStorePort.updateDisplayOrders(userId, orderedIds);
    }

    private Account requireOwnedAccount(List<Account> accounts, Long accountId) {
        return accounts.stream()
                .filter(account -> account.id().equals(accountId))
                .findFirst()
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
    }
}
