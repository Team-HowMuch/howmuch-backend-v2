package com.amateur.howmuchbackend.infra.treasurer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TreasurerConfig {
    @Bean
    Clock treasurerClock() {
        return Clock.systemUTC();
    }
}
