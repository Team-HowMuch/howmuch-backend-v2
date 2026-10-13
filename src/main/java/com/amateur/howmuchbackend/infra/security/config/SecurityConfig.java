package com.amateur.howmuchbackend.infra.security.config;

import com.amateur.howmuchbackend.common.data.BaseResponse;
import com.amateur.howmuchbackend.common.data.ErrorResponse;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.infra.security.jwt.JwtAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(
                        exception ->
                                exception
                                        .authenticationEntryPoint(
                                                (request, response, error) ->
                                                        writeError(
                                                                response,
                                                                CommonStatusCode.UNAUTHORIZED))
                                        .accessDeniedHandler(
                                                (request, response, error) ->
                                                        writeError(
                                                                response,
                                                                CommonStatusCode.FORBIDDEN)))
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .requestMatchers(HttpMethod.OPTIONS, "/**")
                                        .permitAll()
                                        .requestMatchers(
                                                "/api/v2/auth/sms/**",
                                                "/api/v2/auth/phone-number/check",
                                                "/api/v2/auth/signup",
                                                "/api/v2/auth/login",
                                                "/api/v2/auth/token/refresh")
                                        .permitAll()
                                        .requestMatchers("/api/v2/settlement-links/**")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.POST,
                                                "/api/v2/certificate-pairings",
                                                "/api/v2/certificate-pairings/*/registrations")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.GET,
                                                "/api/v2/certificate-pairings/*/pc")
                                        .permitAll()
                                        .requestMatchers(
                                                org.springframework.http.HttpMethod.DELETE,
                                                "/api/v2/certificate-pairings/*/pc")
                                        .permitAll()
                                        .requestMatchers(
                                                "/api/v2/certificate-registrations",
                                                "/api/v2/certificate-registrations/**")
                                        .permitAll()
                                        .requestMatchers(
                                                HttpMethod.GET, "/api/v2/group-invites/{code}")
                                        .permitAll()
                                        .requestMatchers(
                                                "/admin/common-codes",
                                                "/admin/common-codes/**",
                                                "/admin/common-codes.css",
                                                "/admin/common-codes.js")
                                        .permitAll()
                                        .requestMatchers("/api/**")
                                        .authenticated()
                                        .requestMatchers(
                                                "/swagger-ui.html",
                                                "/swagger-ui/**",
                                                "/v3/api-docs/**")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .addFilterBefore(
                        jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .anonymous(Customizer.withDefaults())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    private void writeError(HttpServletResponse response, CommonStatusCode statusCode)
            throws IOException {
        response.setStatus(statusCode.getStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getWriter(),
                new BaseResponse<>(
                        ErrorResponse.of(
                                statusCode.getStatus(),
                                statusCode.getCode(),
                                statusCode.getMessage())));
    }
}
