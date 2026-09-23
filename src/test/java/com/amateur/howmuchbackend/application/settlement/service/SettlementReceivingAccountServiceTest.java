package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SelectSettlementReceivingAccountCommand;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementStatus;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("정산 수령 계좌 Application 흐름")
class SettlementReceivingAccountServiceTest {
    private static final Long USER_ID = 1L;
    private static final UUID SETTLEMENT_ID = UUID.fromString("8b919c13-f797-42fa-b675-73f55dfc47eb");
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 23, 10, 0);

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private SaveSettlementPort saveSettlementPort;
    @Mock private LoadAccountPort loadAccountPort;
    @Mock private BankCatalog bankCatalog;

    private SettlementReceivingAccountService service;

    @BeforeEach
    void setUp() {
        service = new SettlementReceivingAccountService(
                currentActorPort,
                loadSettlementPort,
                saveSettlementPort,
                loadAccountPort,
                bankCatalog
        );
    }

    @Test
    @DisplayName("송금 가능한 본인 계좌만 기본 계좌 우선으로 반환하고 현재 선택을 표시한다")
    void receivingAccounts_eligible_defaultFirstAndCurrentShown() {
        authenticate();
        Settlement settlement = draft().selectReceivingAccount(2L, CREATED_AT.plusMinutes(1));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));
        when(loadAccountPort.findActiveByUserId(USER_ID)).thenReturn(List.of(
                account(2L, false, 0, AccountType.DEPOSIT_TRUST, "11", "KRW"),
                account(3L, false, 2, AccountType.FOREIGN_CURRENCY, "11", "USD"),
                account(1L, true, 1, AccountType.DEPOSIT_TRUST, "11", "KRW"),
                account(4L, false, 3, AccountType.DEPOSIT_TRUST, "12", "KRW")
        ));
        when(bankCatalog.getAllBanks()).thenReturn(List.of(
                new BankCatalogResult("0020", "우리은행", "woori.png")
        ));

        var result = service.get(SETTLEMENT_ID);

        assertThat(result).extracting(item -> item.accountId()).containsExactly(1L, 2L);
        assertThat(result.get(0).institutionName()).isEqualTo("우리은행");
        assertThat(result.get(0).selected()).isFalse();
        assertThat(result.get(1).selected()).isTrue();
    }

    @Test
    @DisplayName("본인의 활성 원화 입출금 계좌를 잠근 초안에 저장한다")
    void receivingAccount_ownedActiveKrw_savedToDraft() {
        authenticate();
        Settlement settlement = draft();
        Account account = account(7L, true, 0, AccountType.DEPOSIT_TRUST, "11", "KRW");
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));
        when(loadSettlementPort.findByIdForUpdate(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));
        when(loadAccountPort.findActiveByIdAndUserIdForUpdate(7L, USER_ID)).thenReturn(Optional.of(account));
        when(saveSettlementPort.update(any(Settlement.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.select(
                SETTLEMENT_ID, new SelectSettlementReceivingAccountCommand(7L)
        );

        ArgumentCaptor<Settlement> captor = ArgumentCaptor.forClass(Settlement.class);
        verify(saveSettlementPort).update(captor.capture());
        assertThat(captor.getValue().receivingAccountId()).isEqualTo(7L);
        assertThat(result.receivingAccountId()).isEqualTo(7L);
        InOrder lockOrder = inOrder(loadAccountPort, loadSettlementPort);
        lockOrder.verify(loadAccountPort).findActiveByIdAndUserIdForUpdate(7L, USER_ID);
        lockOrder.verify(loadSettlementPort).findByIdForUpdate(SETTLEMENT_ID);
    }

    @Test
    @DisplayName("타인 또는 비활성 계좌를 노출하지 않도록 없는 계좌와 같은 오류로 거부한다")
    void receivingAccount_unknownOrIneligible_rejected() {
        authenticate();
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draft()));
        when(loadAccountPort.findActiveByIdAndUserIdForUpdate(99L, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.select(
                SETTLEMENT_ID, new SelectSettlementReceivingAccountCommand(99L)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(AccountStatusCode.ACCOUNT_NOT_FOUND));
        verify(saveSettlementPort, never()).update(any());
    }

    @Test
    @DisplayName("목록 요청을 우회해도 외화 계좌를 수령 계좌로 저장하지 않는다")
    void receivingAccount_foreignCurrency_rejected() {
        authenticate();
        Account foreignAccount = account(
                7L, true, 0, AccountType.FOREIGN_CURRENCY, "11", "USD"
        );
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(draft()));
        when(loadAccountPort.findActiveByIdAndUserIdForUpdate(7L, USER_ID))
                .thenReturn(Optional.of(foreignAccount));

        assertThatThrownBy(() -> service.select(
                SETTLEMENT_ID, new SelectSettlementReceivingAccountCommand(7L)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_RECEIVING_ACCOUNT_NOT_SUPPORTED
                ));
        verify(saveSettlementPort, never()).update(any());
    }

    @Test
    @DisplayName("요청 확정 뒤에는 계좌 조회 전에 수령 계좌 변경을 거부한다")
    void rejectsSelectionOutsideDraft() {
        authenticate();
        Settlement requested = draft().request(CREATED_AT.plusMinutes(1));
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(requested));

        assertThatThrownBy(() -> service.select(
                SETTLEMENT_ID, new SelectSettlementReceivingAccountCommand(7L)
        )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(
                        SettlementStatusCode.SETTLEMENT_NOT_EDITABLE
                ));
        verify(loadAccountPort, never()).findActiveByIdAndUserIdForUpdate(any(), any());
    }

    private void authenticate() {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(USER_ID));
    }

    private Settlement draft() {
        return new Settlement(
                SETTLEMENT_ID,
                USER_ID,
                "회식 정산",
                10_000,
                SettlementStatus.DRAFT,
                CREATED_AT,
                CREATED_AT
        );
    }

    private Account account(
            Long id,
            boolean isDefault,
            int displayOrder,
            AccountType type,
            String productCode,
            String currency
    ) {
        return new Account(
                id,
                USER_ID,
                5L,
                "0020",
                "candidate-" + id,
                "1002123456789",
                type,
                "생활비 통장",
                "****6789",
                new BigDecimal("1200000"),
                currency,
                true,
                productCode,
                isDefault,
                displayOrder
        );
    }
}
