package com.amateur.howmuchbackend.adapter.out.codef;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.external.status.ExternalConnectionStatusCode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodefObservabilityTest {
    @Test
    void codefTelemetry_safeMetricsTraceAndLogs_noMdcLeak() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Tracer tracer = mock(Tracer.class);
        Span span = mock(Span.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.isNoop()).thenReturn(false);
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("registry", registry);
        factory.addBean("tracer", tracer);
        CodefObservability observability = new CodefObservability(
                factory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
                factory.getBeanProvider(Tracer.class));
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger logger = (Logger) LoggerFactory.getLogger(CodefObservability.class);
        logger.addAppender(logs);
        try {
            observability.record("transaction_list", "invalid-org", "invalid-code", null,
                    observability.start());
            observability.record("account_list", "0020", "CF-12834",
                    ApplicationException.of(ExternalConnectionStatusCode.BANK_CREDENTIAL_LOCKED),
                    observability.start());

            assertThat(registry.get("howmuch.codef.requests")
                    .tags("api", "transaction_list", "organization", "unknown", "outcome", "success")
                    .timer().count()).isEqualTo(1);
            verify(span).tag("codef.organization", "unknown");
            verify(span).tag("codef.result_code", "unknown");
            assertThat(logs.list).singleElement().satisfies(log -> {
                assertThat(log.getMDCPropertyMap()).containsEntry("area", "account");
                assertThat(log.getKeyValuePairs().toString())
                        .contains("codef_request_failed", "CF-12834", "bank_credential_locked");
            });
            assertThat(MDC.get("area")).isNull();
        } finally {
            logger.detachAppender(logs);
            logs.stop();
            MDC.clear();
        }
    }
}
