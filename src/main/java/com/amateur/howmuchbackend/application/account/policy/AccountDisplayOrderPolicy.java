package com.amateur.howmuchbackend.application.account.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class AccountDisplayOrderPolicy {
    public List<Long> reorder(List<Account> accounts, Long accountId, int displayOrder) {
        Account target = accounts.stream()
                .filter(account -> account.id().equals(accountId))
                .findFirst()
                .orElseThrow(() -> ApplicationException.of(AccountStatusCode.ACCOUNT_NOT_FOUND));
        if (displayOrder < 0 || displayOrder >= accounts.size()) {
            throw ApplicationException.of(AccountStatusCode.INVALID_ACCOUNT_DISPLAY_ORDER);
        }

        List<Long> orderedIds = new ArrayList<>(accounts.stream()
                .sorted(Comparator.comparingInt(Account::displayOrder).thenComparing(Account::id))
                .map(Account::id)
                .toList());
        orderedIds.remove(target.id());
        orderedIds.add(displayOrder, target.id());
        return List.copyOf(orderedIds);
    }
}
