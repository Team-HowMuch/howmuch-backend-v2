package com.amateur.howmuchbackend.infra.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.common.exception.SafeExceptionLogger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestObservabilityTest {
    private static final String SECRET = "synthetic-pin-otp-jwt-rrn-311";
    private static final String TRACE_ID = "0123456789abcdef0123456789abcdef";
    private static final String SPAN_ID = "0123456789abcdef";
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private RequestTraceFilter filter;
    private Tracer tracer;
    private Span span;

    @BeforeEach
    void setUp() {
        var factory = new StaticListableBeanFactory();
        factory.addBean("registry", registry);
        tracer = mock(Tracer.class);
        span = mock(Span.class);
        TraceContext context = mock(TraceContext.class);
        when(tracer.currentSpan()).thenReturn(span);
        when(span.isNoop()).thenReturn(false);
        when(span.context()).thenReturn(context);
        when(context.traceId()).thenReturn(TRACE_ID);
        factory.addBean("tracer", tracer);
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);
        filter = new RequestTraceFilter(
                new ObjectMapper(),
                factory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class),
                factory.getBeanProvider(Tracer.class)
        );
        logs.start();
        logger(RequestTraceFilter.class).addAppender(logs);
        logger(SafeExceptionLogger.class).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger(RequestTraceFilter.class).detachAppender(logs);
        logger(SafeExceptionLogger.class).detachAppender(logs);
        registry.close();
        MDC.clear();
    }

    @Test
    void requestTrace_activeSpan_headersIgnoredMdcUnchanged() throws Exception {
        MDC.put("traceId", TRACE_ID);
        MDC.put("spanId", SPAN_ID);
        MDC.put("unrelated", "preserved");
        var first = new MockHttpServletResponse();
        var request = request("/api/v2/auth/login");
        request.addHeader(RequestTraceFilter.HEADER, SECRET);
        filter.doFilter(request, first, (req, res) -> {
            assertThat(MDC.get("requestId")).isNull();
            assertThat(MDC.get("traceId")).isEqualTo(TRACE_ID);
            assertThat(first.getHeader(RequestTraceFilter.HEADER)).isEqualTo(TRACE_ID);
        });
        assertThat(MDC.get("traceId")).isEqualTo(TRACE_ID);
        assertThat(MDC.get("spanId")).isEqualTo(SPAN_ID);
        assertThat(MDC.get("unrelated")).isEqualTo("preserved");
        var second = new MockHttpServletResponse();
        filter.doFilter(request("/api/v2/auth/login"), second, (req, res) -> {});
        assertThat(second.getHeader(RequestTraceFilter.HEADER)).isEqualTo(TRACE_ID);
        assertThat(count("auth", "200")).isEqualTo(2);
        assertThat(registry.get("howmuch.http.request.duration").tags("area", "auth", "status", "200")
                .timer().count()).isEqualTo(2);
        org.mockito.Mockito.verify(span, org.mockito.Mockito.atLeastOnce()).tag("area", "auth");
    }

    @Test
    void requestObservability_noActiveSpan_noTraceHeader() throws Exception {
        when(tracer.currentSpan()).thenReturn(null);
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v2/auth/login"), response, (req, res) -> {});
        assertThat(response.getHeader(RequestTraceFilter.HEADER)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "/api/v2/accounts, account",
            "/api/v2/user/me, user",
            "/api/v2/groups/1, group",
            "/api/v2/group-invites/abc, group",
            "/api/v2/notifications, notification",
            "/api/v2/treasurer/questions, treasurer",
            "/api/v2/admin/common-codes, admin"
    })
    void requestObservability_operationalArea_fixedCategory(String path, String area) throws Exception {
        filter.doFilter(request(path), new MockHttpServletResponse(), (req, res) -> {});

        assertThat(count(area, "200")).isEqualTo(1);
        org.mockito.Mockito.verify(span).tag("area", area);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 429, 500, 503})
    void requestFailure_fixedTagsAndSafeLogCorrelation(int status) throws Exception {
        var request = request("/api/v2/settlement-links/" + SECRET + "/resource");
        request.addHeader("Authorization", SECRET);
        request.setQueryString("otp=" + SECRET);
        request.setContent(SECRET.getBytes());
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> response.setStatus(status));
        assertThat(count("settlement", String.valueOf(status))).isEqualTo(1);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getMDCPropertyMap().get("traceId")).isEqualTo(TRACE_ID);
        assertSafe();
    }

    @Test
    void requestFailure_exceptionDetails_notLogged() throws Exception {
        var failure = new IllegalStateException(SECRET, new IllegalArgumentException(SECRET));
        failure.addSuppressed(new IllegalStateException(SECRET));
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v2/settlements/" + SECRET), response, (req, res) -> {
            var result = new GlobalExceptionHandler().handleException(failure);
            response.setStatus(result.getStatusCode().value());
        });
        assertThat(count("settlement", "500")).isEqualTo(1);
        assertThat(logs.list).hasSize(2);
        assertThat(logs.list).allSatisfy(log -> assertThat(log.getMDCPropertyMap().get("traceId"))
                .isEqualTo(TRACE_ID));
        assertSafe();
        assertThat(logs.list.getFirst().getKeyValuePairs().toString()).contains("IllegalStateException", "codeLocations");
        var filterResponse = new MockHttpServletResponse();
        filter.doFilter(request("/api/v2/user/me"), filterResponse, (req, res) -> { throw failure; });
        assertThat(filterResponse.getStatus()).isEqualTo(500);
        assertThat(filterResponse.getContentAsString()).contains("INTERNAL_SERVER_ERROR").doesNotContain(SECRET);
        assertThat(MDC.get("traceId")).isEqualTo(TRACE_ID);
        assertSafe();
    }

    @Test
    void requestFailure_commitOrIoCause_notExposed() {
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request("/io"), response, (req, res) -> {
            throw new IOException(SECRET);
        })).isInstanceOf(IOException.class).hasMessage("Request I/O failed").hasNoCause();
        assertThatThrownBy(() -> filter.doFilter(request("/committed"), response, (req, res) -> {
            response.flushBuffer();
            throw new ServletException(SECRET);
        })).isInstanceOf(ServletException.class).hasMessage("Request failed after response was committed").hasNoCause();
        assertThat(count("other", "500")).isEqualTo(2);
        assertThat(MDC.get("requestId")).isNull();
        assertSafe();
    }

    @Test
    void errorRedispatchReusesIdWithoutCountingTwice() throws Exception {
        var request = request("/unknown");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> response.sendError(404));
        request.setDispatcherType(DispatcherType.ERROR);
        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get("traceId")).isEqualTo(TRACE_ID));
        assertThat(response.getHeader(RequestTraceFilter.HEADER)).isEqualTo(TRACE_ID);
        assertThat(count("other", "404")).isEqualTo(1);
        assertThat(logs.list).hasSize(1);
    }

    @Test
    void asyncRedispatchReusesIdAndCountsOnlyCompletion() throws Exception {
        var request = request("/async");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> request.setAsyncStarted(true));
        assertThat(response.getHeader(RequestTraceFilter.HEADER)).isEqualTo(TRACE_ID);
        assertThat(registry.getMeters()).isEmpty();
        assertThat(MDC.get("traceId")).isEqualTo(TRACE_ID);
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("traceId")).isEqualTo(TRACE_ID);
            response.setStatus(500);
        });
        assertThat(count("other", "500")).isEqualTo(1);
    }

    private MockHttpServletRequest request(String path) { return new MockHttpServletRequest("GET", path); }
    private double count(String area, String status) {
        return registry.get("howmuch.http.requests").tags("area", area, "status", status).counter().count();
    }
    private Logger logger(Class<?> type) { return (Logger) LoggerFactory.getLogger(type); }
    private void assertSafe() {
        assertThat(logs.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(SECRET);
            assertThat(event.getThrowableProxy()).isNull();
            assertThat(event.getKeyValuePairs().toString()).doesNotContain(SECRET);
        });
        assertThat(registry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getTags()).extracting("key").containsExactly("area", "status");
            assertThat(meter.getId().toString()).doesNotContain(SECRET);
        });
    }
}
