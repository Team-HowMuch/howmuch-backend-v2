package com.amateur.howmuchbackend.infra.treasurer.config;

import com.amateur.howmuchbackend.application.treasurer.policy.InquiryParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class TreasurerConfig {
    @Bean
    InquiryParser inquiryParser() {
        return new InquiryParser();
    }

    @Bean
    Clock treasurerClock() {
        return Clock.systemUTC();
    }
}
