package com.amateur.howmuchbackend.infra.persistence.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PersistenceConcurrencyProperties.class)
public class PersistenceConcurrencyConfig {
}
