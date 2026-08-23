package com.amateur.howmuchbackend.application.settlement.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.AccountType;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.springframework.stereotype.Component;

@Component
public class SettlementReceivingAccountPolicy {
    private static final String DEMAND_DEPOSIT = "11";
    private static final String WON = "KRW";

    public boolean supports(Account account) {
        return account.active()
                && account.type() == AccountType.DEPOSIT_TRUST
                && DEMAND_DEPOSIT.equals(account.productCode())
                && account.currency() != null
                && WON.equalsIgnoreCase(account.currency());
    }

    public void validate(Account account) {
        if (!supports(account)) {
            throw ApplicationException.of(
                    SettlementStatusCode.SETTLEMENT_RECEIVING_ACCOUNT_NOT_SUPPORTED
            );
        }
    }
}
