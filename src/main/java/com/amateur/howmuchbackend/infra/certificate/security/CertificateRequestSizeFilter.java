package com.amateur.howmuchbackend.infra.certificate.security;

import com.amateur.howmuchbackend.common.data.*;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Component
@Order(-110)
@ConditionalOnProperty(prefix = "howmuch.certificate", name = "enabled", havingValue = "true")
public class CertificateRequestSizeFilter extends OncePerRequestFilter {
    private static final int LIMIT = 768 * 1024;
    private final ObjectMapper json;
    private final java.util.concurrent.Semaphore uploadSlots =
            new java.util.concurrent.Semaphore(4);

    public CertificateRequestSizeFilter(ObjectMapper json) {
        this.json = json;
    }

    protected boolean shouldNotFilter(HttpServletRequest r) {
        return !(r.getRequestURI().startsWith("/api/v2/certificate-registrations")
                        || r.getRequestURI().startsWith("/api/v2/certificate-claims")
                        || r.getRequestURI().startsWith("/api/v2/certificate-pairings"))
                || !"POST".equals(r.getMethod());
    }

    protected void doFilterInternal(HttpServletRequest r, HttpServletResponse s, FilterChain chain)
            throws ServletException, IOException {
        int limit =
                (r.getRequestURI().startsWith("/api/v2/certificate-claims")
                                || r.getRequestURI().startsWith("/api/v2/certificate-pairings"))
                        ? 4096
                        : LIMIT;
        if (r.getContentLengthLong() > limit) {
            tooLarge(s);
            return;
        }
        boolean upload = r.getRequestURI().endsWith("/upload");
        if (upload && !uploadSlots.tryAcquire()) {
            s.setStatus(429);
            s.setContentType("application/json");
            s.setHeader("Retry-After", "1");
            json.writeValue(
                    s.getOutputStream(),
                    new BaseResponse<>(
                            false,
                            null,
                            ErrorResponse.of(429, "CERTIFICATE_RATE_LIMITED", "잠시 후 다시 시도해주세요.")));
            return;
        }
        try {
            byte[] body = r.getInputStream().readNBytes(limit + 1);
            try {
                if (body.length > limit) {
                    tooLarge(s);
                    return;
                }
                chain.doFilter(
                        new HttpServletRequestWrapper(r) {
                            public ServletInputStream getInputStream() {
                                var input = new ByteArrayInputStream(body);
                                return new ServletInputStream() {
                                    public int read() {
                                        return input.read();
                                    }

                                    public int read(byte[] b, int off, int n) {
                                        return input.read(b, off, n);
                                    }

                                    public boolean isFinished() {
                                        return input.available() == 0;
                                    }

                                    public boolean isReady() {
                                        return true;
                                    }

                                    public void setReadListener(ReadListener listener) {
                                        throw new UnsupportedOperationException(
                                                "Synchronous certificate endpoint");
                                    }
                                };
                            }

                            public BufferedReader getReader() {
                                return new BufferedReader(
                                        new InputStreamReader(
                                                getInputStream(), StandardCharsets.UTF_8));
                            }
                        },
                        s);
            } finally {
                Arrays.fill(body, (byte) 0);
            }
        } finally {
            if (upload) uploadSlots.release();
        }
    }

    private void tooLarge(HttpServletResponse s) throws IOException {
        s.setStatus(413);
        s.setContentType("application/json");
        json.writeValue(
                s.getWriter(),
                new BaseResponse<>(
                        ErrorResponse.of(
                                413, "CERTIFICATE_REQUEST_TOO_LARGE", "인증서 파일 크기가 너무 큽니다.")));
    }
}
