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

import java.net.InetAddress;

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
                    int managementPort = actualPort(environment, "local.management.port", "management.server.port");
                    int applicationPort = actualPort(environment, "local.server.port", "server.port");
                    String allowedCidr = environment.getProperty(
                            "howmuch.observability.management.allowed-cidr", "127.0.0.1/32");
                    return new AuthorizationDecision(managementPort != applicationPort
                            && request.getLocalPort() == managementPort
                            && (isLoopbackAddress(request.getRemoteAddr())
                            || isWithinNetwork(request.getRemoteAddr(), allowedCidr)));
                }).anyRequest().denyAll())
                .build();
    }

    private int actualPort(Environment environment, String runtimeProperty, String configuredProperty) {
        Integer runtimePort = environment.getProperty(runtimeProperty, Integer.class);
        if (runtimePort != null) {
            return runtimePort;
        }
        return environment.getProperty(configuredProperty, Integer.class, -1);
    }

    private boolean isLoopbackAddress(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception exception) {
            return false;
        }
    }

    private boolean isWithinNetwork(String address, String cidr) {
        try {
            String[] parts = cidr.split("/", -1);
            InetAddress remote = InetAddress.getByName(address);
            InetAddress network = InetAddress.getByName(parts[0]);
            byte[] remoteBytes = remote.getAddress();
            byte[] networkBytes = network.getAddress();
            if (remoteBytes.length != networkBytes.length) {
                return false;
            }
            int prefixLength = parts.length == 1
                    ? remoteBytes.length * Byte.SIZE
                    : Integer.parseInt(parts[1]);
            if (prefixLength < 0 || prefixLength > remoteBytes.length * Byte.SIZE) {
                return false;
            }
            for (int index = 0; index < remoteBytes.length; index++) {
                int remaining = prefixLength - index * Byte.SIZE;
                int mask = remaining >= Byte.SIZE ? 0xFF : remaining <= 0 ? 0 : (0xFF << (Byte.SIZE - remaining)) & 0xFF;
                if ((remoteBytes[index] & mask) != (networkBytes[index] & mask)) {
                    return false;
                }
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }
}
