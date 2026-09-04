package com.amateur.howmuchbackend.infra.group.config;

import com.amateur.howmuchbackend.application.port.out.group.TrackGroupInviteRequestPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class GroupInviteWebConfig implements WebMvcConfigurer {
    private static final String PATH = "/api/v2/group-invites/{code}";
    private final TrackGroupInviteRequestPort trackGroupInviteRequestPort;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping(PATH)
                .allowedOrigins("https://howmuch-web.vercel.app")
                .allowedMethods("GET")
                .exposedHeaders(HttpHeaders.RETRY_AFTER);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                if (!"GET".equals(request.getMethod())) {
                    return true;
                }
                // ponytail: proxy/NAT peers share a quota; configure trusted proxy handling before using forwarded IPs.
                long count = trackGroupInviteRequestPort.recordRequest(request.getRemoteAddr());
                if (count > 60) {
                    response.setHeader(HttpHeaders.RETRY_AFTER, "60");
                    throw ApplicationException.of(CommonStatusCode.TOO_MANY_REQUEST);
                }
                return true;
            }
        }).addPathPatterns(PATH);
    }
}
