package com.amateur.howmuchbackend.adapter.in.web.account;

import com.amateur.howmuchbackend.adapter.in.web.account.dto.response.BankCatalogV3WebResponse;
import com.amateur.howmuchbackend.adapter.in.web.common.ApiResponse;
import com.amateur.howmuchbackend.application.port.in.account.GetBankCatalogUseCase;

import com.amateur.howmuchbackend.adapter.in.web.account.docs.BankCatalogDocs;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v3/banks")
public class BankCatalogController implements BankCatalogDocs {
    private final GetBankCatalogUseCase catalog;

    @GetMapping
    @Override
    public ApiResponse<List<BankCatalogV3WebResponse>> getBanks() {
        return ApiResponse.success(
                catalog.getAllBanks().stream().map(BankCatalogV3WebResponse::from).toList());
    }
}
