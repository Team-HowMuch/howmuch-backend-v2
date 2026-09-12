package com.amateur.howmuchbackend.adapter.out.codef;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
class CodefObservability {
    private final ObjectProvider<MeterRegistry> registries;
    private final ObjectProvider<Tracer> tracers;

    long start() {
        return System.nanoTime();
    }

    void record(String api, String organization, String resultCode, Throwable failure, long startedAt) {
        String safeOrganization = organization != null && organization.matches("\\d{4}") ? organization : "unknown";
        String safeResultCode = resultCode != null && resultCode.matches("CF-\\d{5}") ? resultCode : "unknown";
        String outcome = outcome(failure);
        registries.ifAvailable(registry -> Timer.builder("howmuch.codef.requests")
                .description("CODEF API request duration")
                .tags("api", api, "organization", safeOrganization, "outcome", outcome)
                .publishPercentileHistogram()
                .register(registry)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS));

        Tracer tracer = tracers.getIfAvailable();
        Span span = tracer == null ? null : tracer.currentSpan();
        if (span != null && !span.isNoop()) {
            span.tag("codef.api", api);
            span.tag("codef.organization", safeOrganization);
            span.tag("codef.outcome", outcome);
            span.tag("codef.result_code", safeResultCode);
        }
        if (failure != null) {
            String previousArea = MDC.get("area");
            if (previousArea == null) MDC.put("area", "account");
            try {
                log.atWarn().addKeyValue("event", "codef_request_failed")
                        .addKeyValue("api", api)
                        .addKeyValue("organization", safeOrganization)
                        .addKeyValue("outcome", outcome)
                        .addKeyValue("codefCode", safeResultCode)
                        .log("CODEF request failed");
            } finally {
                if (previousArea == null) MDC.remove("area");
            }
        }
    }

    private String outcome(Throwable failure) {
        if (failure == null) return "success";
        if (failure instanceof ApplicationException exception) {
            return exception.getStatusCode().getCode().toLowerCase(Locale.ROOT);
        }
        return "internal_error";
    }
}
