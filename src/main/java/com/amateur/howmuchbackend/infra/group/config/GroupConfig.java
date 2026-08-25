package com.amateur.howmuchbackend.infra.group.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({GroupImageProperties.class, GroupInviteProperties.class})
public class GroupConfig {
}
