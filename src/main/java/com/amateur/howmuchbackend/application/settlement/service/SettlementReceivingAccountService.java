package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.account.catalog.BankCatalog;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.in.settlement.GetSettlementReceivingAccountsUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.SelectSettlementReceivingAccountUseCase;
import com.amateur.howmuchbackend.application.port.in.settlement.command.SelectSettlementReceivingAccountCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementReceivingAccountResult;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementResult;
import com.amateur.howmuchbackend.application.port.out.account.LoadAccountPort;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveSettlementPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SettlementReceivingAccountService implements
        GetSettlementReceivingAccountsUseCase,
        SelectSettlementReceivingAccountUseCase {
    private final CurrentActorPort currentActorPort;
    private final LoadSettlementPort loadSettlementPort;
    private final SaveSettlementPort saveSettlementPort;
    private final LoadAccountPort loadAccountPort;
    private final BankCatalog bankCatalog;

    @Override
    @Transactional(readOnly = true)
    public List<SettlementReceivingAccountResult> get(UUID settlementId) {
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement settlement = loadOwnedSettlement(settlementId, actor.userId());
        settlement.ensureReceivingAccountEditable();
        Map<String, BankCatalogResult> institutions = bankCatalog.getBanks().stream()
                .collect(Collectors.toMap(BankCatalogResult::organization, Function.identity()));
        return loadAccountPort.findActiveByUserId(actor.userId()).stream()
                .filter(Account::isActiveWonDemandDeposit)
                .sorted(accountComparator())
                .map(account -> toResult(account, institutions.get(account.organization()), settlement))
                .toList();
    }

    @Override
    @Transactional
    public SettlementResult select(
            UUID settlementId,
            SelectSettlementReceivingAccountCommand command
    ) {
        validateCommand(command);
        CurrentActor actor = currentActorPort.getCurrentActor();
        Settlement candidate = loadOwnedSettlement(settlementId, actor.userId());
        candidate.ensureReceivingAccountEditable();
        Account account = loadAccountPort.findActiveByIdAndUserIdForUpdate(
                        command.receivingAccountId(), actor.userId()
                )
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
        validateReceivingAccount(account);
        Settlement settlement = loadOwnedSettlementForUpdate(settlementId, actor.userId());
        settlement.ensureReceivingAccountEditable();
        Settlement updated = settlement.selectReceivingAccount(
                account.id(), LocalDateTime.now(ZoneOffset.UTC)
        );
        return SettlementResult.from(saveSettlementPort.update(updated));
    }

    private SettlementReceivingAccountResult toResult(
            Account account,
            BankCatalogResult institution,
            Settlement settlement
    ) {
        return new SettlementReceivingAccountResult(
                account.id(),
                account.organization(),
                institution == null ? account.organization() : institution.name(),
                institution == null ? null : institution.iconUrl(),
                account.name(),
                account.maskedNumber(),
                account.isDefault(),
                account.id().equals(settlement.receivingAccountId())
        );
    }

    private Comparator<Account> accountComparator() {
        return Comparator.comparing(Account::isDefault).reversed()
                .thenComparingInt(Account::displayOrder)
                .thenComparing(Account::id);
    }

    private void validateCommand(SelectSettlementReceivingAccountCommand command) {
        if (command == null
                || command.receivingAccountId() == null
                || command.receivingAccountId() <= 0) {
            throw ApplicationException.of(
                    SettlementStatusCode.INVALID_SETTLEMENT_RECEIVING_ACCOUNT_ID
            );
        }
    }

    private void validateReceivingAccount(Account account) {
        if (!account.isActiveWonDemandDeposit()) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_RECEIVING_ACCOUNT_NOT_SUPPORTED
            );
        }
    }

    private Settlement loadOwnedSettlement(UUID settlementId, Long userId) {
        return verifyOwnership(loadSettlementPort.findById(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                )), userId);
    }

    private Settlement loadOwnedSettlementForUpdate(UUID settlementId, Long userId) {
        return verifyOwnership(loadSettlementPort.findByIdForUpdate(settlementId)
                .orElseThrow(() -> ApplicationException.of(
                        SettlementStatusCode.SETTLEMENT_NOT_FOUND
                )), userId);
    }

    private Settlement verifyOwnership(Settlement settlement, Long userId) {
        if (!settlement.isCreatedBy(userId)) {
            throw ApplicationException.of(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED);
        }
        return settlement;
    }
}
