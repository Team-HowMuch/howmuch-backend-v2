package com.amateur.howmuchbackend.infra.certificate;

import static org.assertj.core.api.Assertions.*;

import com.amateur.howmuchbackend.infra.certificate.security.CertificateRequestSizeFilter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

import tools.jackson.databind.ObjectMapper;

import java.util.concurrent.atomic.AtomicBoolean;

class CertificateRequestSizeFilterTest {
    @Test
    void chunkedOversizedUploadIsRejectedBeforeController() throws Exception {
        var request =
                new MockHttpServletRequest(
                        "POST", "/api/v2/certificate-registrations/fixture/upload") {
                    @Override
                    public long getContentLengthLong() {
                        return -1;
                    }
                };
        request.setContent(new byte[768 * 1024 + 1]);
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        new CertificateRequestSizeFilter(new ObjectMapper())
                .doFilter(request, response, (r, s) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(called).isFalse();
    }

    @Test
    void claimBodiesHaveTheirOwnSmallerBound() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v2/certificate-claims");
        request.setContent(new byte[4097]);
        var response = new MockHttpServletResponse();
        new CertificateRequestSizeFilter(new ObjectMapper())
                .doFilter(
                        request,
                        response,
                        (r, s) -> {
                            throw new AssertionError("Oversized claim reached controller");
                        });
        assertThat(response.getStatus()).isEqualTo(413);
    }
}
