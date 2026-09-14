package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.out.account.DeleteAccountPort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.external.ExternalConnectionStorePort;
import com.amateur.howmuchbackend.application.port.out.settlement.SettlementReceivingAccountUsagePort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.external.ExternalConnection;
import com.amateur.howmuchbackend.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("계좌 삭제 Application 흐름")
class AccountDeletionServiceTest {
    private static final Long USER_ID = 1L;
    private static final Long CONNECTION_ID = 7L;

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadUserPort loadUserPort;
    @Mock private LoadAccountPort loadAccountPort;
    @Mock private DeleteAccountPort deleteAccountPort;
    @Mock private ExternalConnectionStorePort connectionStorePort;
    @Mock private SettlementReceivingAccountUsagePort settlementUsagePort;
    @Mock private User user;

    @Test
    @DisplayName("기본 계좌를 삭제하면 정산 참조를 분리하고 다음 계좌를 기본으로 승격한다")
    void deletesDefaultAccountAndPromotesNext() {
        Account deleted = account(10L, true, 2);
        Account next = account(12L, false, 1);
        Account later = account(11L, false, 3);
        givenOwnedLockedAccount(deleted);
        when(loadAccountPort.findActiveByUserId(USER_ID)).thenReturn(List.of(deleted, later, next));

        service().delete(deleted.id());

        verify(settlementUsagePort).detachCompletedByReceivingAccountId(deleted.id());
        verify(deleteAccountPort).delete(deleted.id(), next.id());
    }

    @Test
    @DisplayName("진행 중 정산이 수령 계좌로 사용하면 아무것도 변경하지 않고 409로 거부한다")
    void rejectsAccountUsedByInProgressSettlement() {
        Account account = account(10L, true, 0);
        givenOwnedLockedAccount(account);
        when(settlementUsagePort.existsInProgressByReceivingAccountId(account.id())).thenReturn(true);

        assertThatThrownBy(() -> service().delete(account.id()))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(AccountStatusCode.ACCOUNT_IN_USE_BY_SETTLEMENT));

        verify(settlementUsagePort, never()).detachCompletedByReceivingAccountId(account.id());
        verifyNoInteractions(deleteAccountPort);
    }

    @Test
    @DisplayName("타인 또는 삭제된 계좌는 연결 잠금 전에 404로 거부한다")
    void rejectsUnknownAccount() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(user.active()).thenReturn(true);
        when(loadUserPort.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(loadAccountPort.findActiveByIdAndUserId(99L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(99L))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(AccountStatusCode.ACCOUNT_NOT_FOUND));

        verifyNoInteractions(connectionStorePort, settlementUsagePort, deleteAccountPort);
    }

    @Test
    @DisplayName("기본 계좌가 아니면 기본 계좌 승격 없이 삭제한다")
    void deletesNonDefaultAccountWithoutPromotion() {
        Account account = account(10L, false, 2);
        givenOwnedLockedAccount(account);

        service().delete(account.id());

        verify(loadAccountPort, never()).findActiveByUserId(USER_ID);
        verify(deleteAccountPort).delete(account.id(), null);
    }

    private void givenOwnedLockedAccount(Account account) {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(user.active()).thenReturn(true);
        when(loadUserPort.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(user));
        when(loadAccountPort.findActiveByIdAndUserId(account.id(), USER_ID)).thenReturn(Optional.of(account));
        when(connectionStorePort.findActiveByIdAndUserId(CONNECTION_ID, USER_ID))
                .thenReturn(Optional.of(new ExternalConnection(CONNECTION_ID, USER_ID, "CID-1")));
        when(loadAccountPort.findActiveByIdAndUserIdForUpdate(account.id(), USER_ID))
                .thenReturn(Optional.of(account));
    }

    private AccountDeletionService service() {
        return new AccountDeletionService(
                currentActorPort,
                loadUserPort,
                loadAccountPort,
                deleteAccountPort,
                connectionStorePort,
                settlementUsagePort
        );
    }

    private Account account(Long id, boolean isDefault, int displayOrder) {
        return new Account(
                id,
                USER_ID,
                CONNECTION_ID,
                "0020",
                "candidate-" + id,
                "1002123456789",
                AccountType.DEPOSIT_TRUST,
                "생활비 통장",
                "****6789",
                new BigDecimal("1250000"),
                "KRW",
                true,
                "11",
                isDefault,
                displayOrder
        );
    }
}
