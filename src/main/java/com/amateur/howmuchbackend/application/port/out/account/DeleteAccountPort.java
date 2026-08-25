package com.amateur.howmuchbackend.application.port.out.account;

public interface DeleteAccountPort {
    void delete(Long accountId, Long nextDefaultAccountId);
}
