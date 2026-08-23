package com.amateur.howmuchbackend.adapter.out.persistence.account.mapper;

import com.amateur.howmuchbackend.adapter.out.persistence.account.entity.AccountJpaEntity;
import com.amateur.howmuchbackend.domain.account.Account;

public final class AccountMapper {
    private AccountMapper() {
    }

    public static Account toDomain(AccountJpaEntity entity) {
        return toDomain(entity, null);
    }

    public static Account toDomain(AccountJpaEntity entity, String externalAccountKey) {
        return new Account(
                entity.getId(),
                entity.getUser().getId(),
                entity.getExternalConnection().getId(),
                entity.getOrganization(),
                entity.getCandidateKey(),
                externalAccountKey,
                entity.getType(),
                entity.getName(),
                entity.getMaskedNumber(),
                entity.getBalance(),
                entity.getCurrency(),
                entity.isActive(),
                entity.getProductCode(),
                entity.isDefault(),
                entity.getDisplayOrder()
        );
    }
}
