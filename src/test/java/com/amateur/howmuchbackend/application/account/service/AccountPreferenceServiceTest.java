package com.amateur.howmuchbackend.application.account.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.command.UpdateAccountDisplayOrderCommand;
import com.amateur.howmuchbackend.application.port.out.account.AccountPreferenceStorePort;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountPreferenceServiceTest {
    private static final Long USER_ID = 1L;

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadAccountPort loadAccountPort;
    @Mock private AccountPreferenceStorePort accountPreferenceStorePort;

    @Test
    void setsOwnedActiveAccountAsDefault() {
        givenAccounts(account(10L, false, 0), account(11L, true, 1));

        service().setDefault(10L);

        verify(accountPreferenceStorePort).setDefault(USER_ID, 10L);
    }

    @Test
    void movesAccountAndNormalizesDisplayOrders() {
        givenAccounts(
                account(10L, true, 0),
                account(11L, false, 1),
                account(12L, false, 2)
        );

        service().updateDisplayOrder(new UpdateAccountDisplayOrderCommand(10L, 2));

        verify(accountPreferenceStorePort).updateDisplayOrders(
                USER_ID,
                List.of(11L, 12L, 10L)
        );
    }

    @Test
    void rejectsDisplayOrderOutsideActiveAccountRange() {
        givenAccounts(account(10L, true, 0), account(11L, false, 1));

        assertThatThrownBy(() -> service().updateDisplayOrder(
                new UpdateAccountDisplayOrderCommand(10L, 2)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode())
                        .isEqualTo(AccountStatusCode.INVALID_ACCOUNT_DISPLAY_ORDER));

        verify(accountPreferenceStorePort, never()).updateDisplayOrders(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsUnknownOrInactiveAccount() {
        givenAccounts(account(10L, true, 0));

        assertThatThrownBy(() -> service().setDefault(99L))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(AccountStatusCode.ACCOUNT_NOT_FOUND));

        verify(accountPreferenceStorePort, never()).setDefault(USER_ID, 99L);
    }

    private void givenAccounts(Account... accounts) {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
        when(loadAccountPort.findActiveByUserIdForUpdate(USER_ID)).thenReturn(List.of(accounts));
    }

    private AccountPreferenceService service() {
        return new AccountPreferenceService(
                currentActorPort,
                loadAccountPort,
                accountPreferenceStorePort
        );
    }

    private Account account(Long id, boolean isDefault, int displayOrder) {
        return new Account(
                id,
                USER_ID,
                7L,
                "0020",
                "candidate-" + id,
                null,
                AccountType.DEPOSIT_TRUST,
                "생활비 통장",
                "****6789",
                BigDecimal.ZERO,
                "KRW",
                true,
                "11",
                isDefault,
                displayOrder
        );
    }
}
