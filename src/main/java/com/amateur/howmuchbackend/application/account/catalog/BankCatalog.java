package com.amateur.howmuchbackend.application.account.catalog;

import com.amateur.howmuchbackend.application.port.in.account.GetBankCatalogUseCase;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankCatalog implements GetBankCatalogUseCase {
    private static final String GROUP_CODE = "BANK";

    private final LoadCommonCodePort loadCommonCodePort;

    @Override
    public List<BankCatalogResult> getBanks() {
        return loadCommonCodePort.findActiveByGroupCode(GROUP_CODE).stream()
                .map(bank -> new BankCatalogResult(bank.code(), bank.name(), bank.attr1()))
                .toList();
    }

    public void validate(String organization) {
        if (!loadCommonCodePort.existsActiveByGroupCodeAndCode(GROUP_CODE, organization)) {
            throw ApplicationException.of(ExternalConnectionStatusCode.BANK_NOT_SUPPORTED);
        }
    }
}
