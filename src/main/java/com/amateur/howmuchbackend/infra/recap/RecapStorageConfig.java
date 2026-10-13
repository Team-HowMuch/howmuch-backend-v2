package com.amateur.howmuchbackend.infra.recap;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RecapImageProperties.class)
public class RecapStorageConfig {}
