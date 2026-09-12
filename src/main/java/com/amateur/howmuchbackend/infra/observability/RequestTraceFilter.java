package com.amateur.howmuchbackend.infra.observability;

import com.amateur.howmuchbackend.common.data.BaseResponse;
import com.amateur.howmuchbackend.common.data.ErrorResponse;
import com.amateur.howmuchbackend.common.exception.SafeExceptionLogger;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
@Slf4j
public class RequestTraceFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Trace-ID";
    private static final String RECORDED_ATTRIBUTE = RequestTraceFilter.class.getName() + ".recorded";
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MeterRegistry> registries;
    private final ObjectProvider<Tracer> tracers;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override
    protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
        String area = area(request);
        String previousArea = MDC.get("area");
        MDC.put("area", area);
        tagTrace(area);
        setTraceHeader(response);
        long startedAt = System.nanoTime();
        int failureStatus = 0;
        try {
            filterChain.doFilter(request, response);
        } catch (ServletException | RuntimeException exception) {
            failureStatus = 500;
            SafeExceptionLogger.log(exception, 500);
            if (!response.isCommitted()) {
                response.resetBuffer();
                var code = CommonStatusCode.INTERNAL_SERVER_ERROR;
                response.setStatus(code.getStatus());
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(response.getWriter(), new BaseResponse<>(ErrorResponse.of(
                        code.getStatus(), code.getCode(), code.getMessage())));
            } else {
                throw new ServletException("Request failed after response was committed");
            }
        } catch (IOException exception) {
            failureStatus = 500;
            SafeExceptionLogger.log(exception, 500);
            throw new IOException("Request I/O failed");
        } finally {
            if (!request.isAsyncStarted() && request.getAttribute(RECORDED_ATTRIBUTE) == null) {
                request.setAttribute(RECORDED_ATTRIBUTE, true);
                int status = failureStatus == 0 ? response.getStatus() : failureStatus;
                String statusTag = status >= 100 && status <= 599 ? Integer.toString(status) : "other";
                registries.ifAvailable(registry -> recordRequest(registry, area, statusTag, startedAt));
                if (status >= 400) {
                    log.atWarn().addKeyValue("event", "request_failed")
                            .addKeyValue("status", status).log("Request failed");
                }
            }
            if (previousArea == null) MDC.remove("area");
            else MDC.put("area", previousArea);
        }
    }

    private void recordRequest(MeterRegistry registry, String area, String status, long startedAt) {
        registry.counter("howmuch.http.requests", "area", area, "status", status).increment();
        Timer.builder("howmuch.http.request.duration")
                .description("Completed HTTP request duration")
                .tags("area", area, "status", status)
                .publishPercentileHistogram()
                .register(registry)
                .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
    }

    private void tagTrace(String area) {
        Tracer tracer = tracers.getIfAvailable();
        Span span = tracer == null ? null : tracer.currentSpan();
        if (span != null && !span.isNoop()) span.tag("area", area);
    }

    private void setTraceHeader(HttpServletResponse response) {
        Tracer tracer = tracers.getIfAvailable();
        if (tracer == null) {
            return;
        }
        Span span = tracer.currentSpan();
        if (span == null || span.isNoop() || span.context() == null) {
            return;
        }
        String traceId = span.context().traceId();
        if (traceId != null && !traceId.isBlank() && !traceId.matches("0+")) {
            response.setHeader(HEADER, traceId);
        }
    }

    private String area(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/v2/auth/")) return "auth";
        if (path.startsWith("/api/v2/accounts")) return "account";
        if (path.startsWith("/api/v2/user")) return "user";
        if (path.startsWith("/api/v2/groups") || path.startsWith("/api/v2/group-invites")) return "group";
        if (path.startsWith("/api/v2/settlements/") || path.equals("/api/v2/settlements")
                || path.startsWith("/api/v2/settlement-links/")) return "settlement";
        if (path.startsWith("/api/v2/notifications")) return "notification";
        if (path.startsWith("/api/v2/treasurer")) return "treasurer";
        if (path.startsWith("/api/v2/admin")) return "admin";
        return "other";
    }
}
