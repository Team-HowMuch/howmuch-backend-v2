package com.amateur.howmuchbackend.infra.profile.config;

import com.amateur.howmuchbackend.application.user.policy.ProfileImagePolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProfileImageProperties.class)
public class ProfileImageConfig {
    @Bean
    ProfileImagePolicy profileImagePolicy() {
        return new ProfileImagePolicy();
    }
}
