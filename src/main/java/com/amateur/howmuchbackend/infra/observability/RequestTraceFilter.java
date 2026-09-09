package com.amateur.howmuchbackend.infra.observability;

import com.amateur.howmuchbackend.common.data.BaseResponse;
import com.amateur.howmuchbackend.common.data.ErrorResponse;
import com.amateur.howmuchbackend.common.exception.SafeExceptionLogger;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
@RequiredArgsConstructor
@Slf4j
public class RequestTraceFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    private static final String ID_ATTRIBUTE = RequestTraceFilter.class.getName() + ".id";
    private static final String RECORDED_ATTRIBUTE = RequestTraceFilter.class.getName() + ".recorded";
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MeterRegistry> registries;

    @Override
    protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override
    protected boolean shouldNotFilterErrorDispatch() { return false; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {
        String requestId = (String) request.getAttribute(ID_ATTRIBUTE);
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
            request.setAttribute(ID_ATTRIBUTE, requestId);
        }
        String previousId = MDC.get("requestId");
        MDC.put("requestId", requestId);
        response.setHeader(HEADER, requestId);
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
            try {
                if (!request.isAsyncStarted() && request.getAttribute(RECORDED_ATTRIBUTE) == null) {
                    request.setAttribute(RECORDED_ATTRIBUTE, true);
                    int status = failureStatus == 0 ? response.getStatus() : failureStatus;
                    String area = area(request);
                    registries.ifAvailable(registry -> registry.counter("howmuch.http.requests",
                            "area", area, "status", status >= 100 && status <= 599
                                    ? Integer.toString(status) : "other").increment());
                    if (status >= 400) {
                        log.atWarn().addKeyValue("event", "request_failed")
                                .addKeyValue("status", status).addKeyValue("area", area).log("Request failed");
                    }
                }
            } finally {
                if (previousId == null) MDC.remove("requestId");
                else MDC.put("requestId", previousId);
            }
        }
    }

    private String area(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (path.startsWith("/api/v2/auth/")) return "auth";
        if (path.startsWith("/api/v2/settlements/") || path.equals("/api/v2/settlements")
                || path.startsWith("/api/v2/settlement-links/")) return "settlement";
        return "other";
    }
}
