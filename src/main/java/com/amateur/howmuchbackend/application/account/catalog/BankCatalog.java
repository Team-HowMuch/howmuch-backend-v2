package com.amateur.howmuchbackend.application.account.catalog;

import com.amateur.howmuchbackend.application.port.in.account.GetBankCatalogUseCase;
import com.amateur.howmuchbackend.application.port.in.account.result.BankCatalogResult;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodePort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankCatalog implements GetBankCatalogUseCase {
    private static final String GROUP_CODE = "BANK";
    private static final Set<String> ID_PASSWORD_BANKS = Set.of(
            "0003", "0004", "0011", "0020", "0031", "0037",
            "0039", "0045", "0071", "0081", "0088"
    );

    private final LoadCommonCodePort loadCommonCodePort;

    @Override
    public List<BankCatalogResult> getBanks() {
        return getAllBanks().stream()
                .filter(bank -> ID_PASSWORD_BANKS.contains(bank.organization()))
                .toList();
    }

    public List<BankCatalogResult> getAllBanks() {
        return loadCommonCodePort.findActiveByGroupCode(GROUP_CODE).stream()
                .map(bank -> new BankCatalogResult(
                        bank.code(), bank.name(), bank.attr1(), "1".equals(bank.attr3())
                ))
                .toList();
    }

    public boolean requiresAccountCredentials(String organization) {
        return loadCommonCodePort.findActiveByGroupCodeAndCode(GROUP_CODE, organization)
                .map(bank -> "1".equals(bank.attr3()))
                .orElse(false);
    }

    public void validate(String organization) {
        if (!ID_PASSWORD_BANKS.contains(organization)
                || !loadCommonCodePort.existsActiveByGroupCodeAndCode(GROUP_CODE, organization)) {
            throw ApplicationException.of(ExternalConnectionStatusCode.BANK_NOT_SUPPORTED);
        }
    }

    /** 화면에 보여줄 은행 이름. 토스 딥링크용 표기(attr2)와 다를 수 있어 분리한다. */
    public Optional<String> findBankName(String organization) {
        return loadCommonCodePort.findActiveByGroupCodeAndCode(GROUP_CODE, organization)
                .map(CommonCode::name)
                .filter(bankName -> !bankName.isBlank());
    }

    public Optional<String> findTossDeepLinkBankName(String organization) {
        return loadCommonCodePort.findActiveByGroupCodeAndCode(GROUP_CODE, organization)
                .map(CommonCode::attr2)
                .filter(bankName -> !bankName.isBlank());
    }
}
