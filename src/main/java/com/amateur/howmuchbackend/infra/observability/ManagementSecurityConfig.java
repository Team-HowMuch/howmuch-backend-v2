package com.amateur.howmuchbackend.infra.observability;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class ManagementSecurityConfig {
    @Bean
    @Order(0)
    SecurityFilterChain managementSecurityFilterChain(HttpSecurity http, Environment environment) throws Exception {
        String basePath = environment.getProperty("management.endpoints.web.base-path", "/actuator");
        return http.securityMatcher(basePath, basePath + "/**")
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint((request, response, error) -> response.setStatus(403))
                        .accessDeniedHandler((request, response, error) -> response.setStatus(403)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).access((authentication, context) -> {
                    var request = context.getRequest();
                    // Use the actual bound management port, including random-port tests.
                    int managementPort = environment.getProperty("local.management.port", Integer.class, -1);
                    int applicationPort = environment.getProperty("local.server.port", Integer.class, -1);
                    return new AuthorizationDecision(managementPort != applicationPort
                            && request.getLocalPort() == managementPort
                            && "127.0.0.1".equals(request.getLocalAddr()));
                }).anyRequest().denyAll())
                .build();
    }
}
