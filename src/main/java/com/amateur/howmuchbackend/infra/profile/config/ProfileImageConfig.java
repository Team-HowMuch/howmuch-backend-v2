package com.amateur.howmuchbackend.infra.profile.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ProfileImageProperties.class)
public class ProfileImageConfig {
}
