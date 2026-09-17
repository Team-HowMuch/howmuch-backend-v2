package com.amateur.howmuchbackend.infra.notification.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ApnsProperties.class)
public class ApnsConfig {
}
