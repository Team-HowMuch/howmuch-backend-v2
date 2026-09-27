package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.DeleteAccountUseCase;
import com.amateur.howmuchbackend.application.port.out.account.DeleteAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReceivingAccountUsagePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.auth.status.AuthStatusCode;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

@Service
@RequiredArgsConstructor
public class AccountDeletionService implements DeleteAccountUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadUserPort loadUserPort;
    private final LoadAccountPort loadAccountPort;
    private final DeleteAccountPort deleteAccountPort;
    private final ExternalConnectionStorePort connectionStorePort;
    private final SettlementReceivingAccountUsagePort settlementUsagePort;

    @Override
    @Transactional
    public void delete(Long accountId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        loadUserPort.findByIdForUpdate(actor.userId())
                .filter(User::active)
                .orElseThrow(() -> ApplicationException.of(AuthStatusCode.USER_NOT_FOUND));
        Account account = ownedAccount(accountId, actor.userId());
        connectionStorePort.findActiveByIdAndUserId(account.externalConnectionId(), actor.userId())
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
        account = loadAccountPort.findActiveByIdAndUserIdForUpdate(accountId, actor.userId())
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));

        if (settlementUsagePort.existsInProgressByReceivingAccountId(account.id())) {
            throw ApplicationException.of(AccountStatusCode.ACCOUNT_IN_USE_BY_SETTLEMENT);
        }

        settlementUsagePort.detachCompletedOrDeletedByReceivingAccountId(account.id());
        deleteAccountPort.delete(account.id(), nextDefaultAccountId(account));
    }

    private Account ownedAccount(Long accountId, Long userId) {
        return loadAccountPort.findActiveByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
    }

    private Long nextDefaultAccountId(Account deletedAccount) {
        if (!deletedAccount.isDefault()) {
            return null;
        }
        return loadAccountPort.findActiveByUserId(deletedAccount.userId()).stream()
                .filter(account -> !account.id().equals(deletedAccount.id()))
                .min(Comparator.comparingInt(Account::displayOrder).thenComparing(Account::id))
                .map(Account::id)
                .orElse(null);
    }
}
