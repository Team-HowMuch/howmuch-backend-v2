package com.amateur.howmuchbackend.infra.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import com.amateur.howmuchbackend.common.exception.SafeExceptionLogger;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class RequestObservabilityTest {
    private static final String SECRET = "synthetic-pin-otp-jwt-rrn-311";
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>() {
        @Override protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    };
    private RequestTraceFilter filter;

    @BeforeEach
    void setUp() {
        var factory = new StaticListableBeanFactory();
        factory.addBean("registry", registry);
        filter = new RequestTraceFilter(new ObjectMapper(), factory.getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
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
    void generatesUniqueIdsIgnoresUntrustedInputAndRestoresMdc() throws Exception {
        MDC.put("requestId", "outer-context");
        MDC.put("unrelated", "preserved");
        var first = new MockHttpServletResponse();
        var request = request("/api/v2/auth/login");
        request.addHeader(RequestTraceFilter.HEADER, SECRET);
        filter.doFilter(request, first, (req, res) -> {
            assertThat(MDC.get("requestId")).isEqualTo(first.getHeader(RequestTraceFilter.HEADER));
            UUID.fromString(MDC.get("requestId"));
        });
        assertThat(MDC.get("requestId")).isEqualTo("outer-context");
        assertThat(MDC.get("unrelated")).isEqualTo("preserved");
        MDC.remove("requestId");
        var second = new MockHttpServletResponse();
        filter.doFilter(request("/api/v2/auth/login"), second, (req, res) -> {});
        assertThat(second.getHeader(RequestTraceFilter.HEADER)).isNotEqualTo(first.getHeader(RequestTraceFilter.HEADER));
        assertThat(MDC.get("requestId")).isNull();
        assertThat(count("auth", "200")).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 429, 500, 503})
    void failuresUseFixedTagsAndCorrelateLogsWithoutRequestValues(int status) throws Exception {
        var request = request("/api/v2/settlement-links/" + SECRET + UUID.randomUUID());
        request.addHeader("Authorization", SECRET);
        request.setQueryString("otp=" + SECRET);
        request.setContent(SECRET.getBytes());
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> response.setStatus(status));
        assertThat(count("settlement", String.valueOf(status))).isEqualTo(1);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getMDCPropertyMap().get("requestId"))
                .isEqualTo(response.getHeader(RequestTraceFilter.HEADER));
        assertSafe();
    }

    @Test
    void adviceAndFilterExceptionsNeverLogMessagesCausesOrSuppressedValues() throws Exception {
        var failure = new IllegalStateException(SECRET, new IllegalArgumentException(SECRET));
        failure.addSuppressed(new IllegalStateException(SECRET));
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/api/v2/settlements/" + SECRET), response, (req, res) -> {
            var result = new GlobalExceptionHandler().handleException(failure);
            response.setStatus(result.getStatusCode().value());
        });
        assertThat(count("settlement", "500")).isEqualTo(1);
        assertThat(logs.list).hasSize(2);
        assertThat(logs.list).allSatisfy(log -> assertThat(log.getMDCPropertyMap().get("requestId"))
                .isEqualTo(response.getHeader(RequestTraceFilter.HEADER)));
        assertSafe();
        assertThat(logs.list.getFirst().getKeyValuePairs().toString()).contains("IllegalStateException", "codeLocations");
        var filterResponse = new MockHttpServletResponse();
        filter.doFilter(request("/api/v2/user/me"), filterResponse, (req, res) -> { throw failure; });
        assertThat(filterResponse.getStatus()).isEqualTo(500);
        assertThat(filterResponse.getContentAsString()).contains("INTERNAL_SERVER_ERROR").doesNotContain(SECRET);
        assertThat(MDC.get("requestId")).isNull();
        assertSafe();
    }

    @Test
    void committedAndIoFailuresDoNotEscapeWithSensitiveCause() {
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
        String id = response.getHeader(RequestTraceFilter.HEADER);
        request.setDispatcherType(DispatcherType.ERROR);
        filter.doFilter(request, response, (req, res) -> assertThat(MDC.get("requestId")).isEqualTo(id));
        assertThat(count("other", "404")).isEqualTo(1);
        assertThat(logs.list).hasSize(1);
    }

    @Test
    void asyncRedispatchReusesIdAndCountsOnlyCompletion() throws Exception {
        var request = request("/async");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> request.setAsyncStarted(true));
        String id = response.getHeader(RequestTraceFilter.HEADER);
        assertThat(registry.getMeters()).isEmpty();
        assertThat(MDC.get("requestId")).isNull();
        request.setAsyncStarted(false);
        request.setDispatcherType(DispatcherType.ASYNC);
        filter.doFilter(request, response, (req, res) -> {
            assertThat(MDC.get("requestId")).isEqualTo(id);
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
