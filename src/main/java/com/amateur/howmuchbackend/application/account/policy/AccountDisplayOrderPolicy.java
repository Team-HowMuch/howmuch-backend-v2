package com.amateur.howmuchbackend.application.account.policy;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.account.Account;
import com.amateur.howmuchbackend.domain.account.status.AccountStatusCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * 일괄 저장 요청이 활성 계좌 전체를 정확히 한 번씩 덮는지 확인하고 저장할 순서를 돌려준다.
     * 빠진 계좌가 있으면 그 계좌만 옛 displayOrder를 유지해 순서 값이 중복되므로 전원 포함을 강제한다.
     */
    public List<Long> validateReplacement(List<Account> accounts, List<Long> accountIds) {
        if (accountIds == null || accountIds.size() != accounts.size()) {
            throw ApplicationException.of(
                    AccountStatusCode.INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST
            );
        }

        Set<Long> ownedIds = accounts.stream()
                .map(Account::id)
                .collect(Collectors.toUnmodifiableSet());
        Set<Long> seenIds = new HashSet<>();
        for (Long accountId : accountIds) {
            if (accountId == null || !ownedIds.contains(accountId) || !seenIds.add(accountId)) {
                throw ApplicationException.of(
                        AccountStatusCode.INVALID_ACCOUNT_DISPLAY_ORDER_REQUEST
                );
            }
        }
        return List.copyOf(accountIds);
    }
}
