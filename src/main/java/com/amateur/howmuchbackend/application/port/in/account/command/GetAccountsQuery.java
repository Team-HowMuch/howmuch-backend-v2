package com.amateur.howmuchbackend.application.port.in.account.command;

import com.amateur.howmuchbackend.domain.account.AccountSort;

public record GetAccountsQuery(String organization, AccountSort sort) {
    public GetAccountsQuery {
        sort = sort == null ? AccountSort.DEFAULT : sort;
    }
}
