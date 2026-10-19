package com.amateur.howmuchbackend.application.port.in.recap.result;

import com.amateur.howmuchbackend.application.recap.model.RecapBenefit;

import java.time.LocalDateTime;

public record RecapBenefitResult(String title, String reason, LocalDateTime expiresAt, String url, String logoUrl) {
    public static RecapBenefitResult from(RecapBenefit benefit) {
        return new RecapBenefitResult(benefit.title(), benefit.reason(), benefit.expiresAt(), benefit.url(), benefit.logoUrl());
    }
}
