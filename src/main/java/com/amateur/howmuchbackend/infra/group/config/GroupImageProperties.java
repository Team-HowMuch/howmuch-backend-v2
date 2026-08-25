package com.amateur.howmuchbackend.infra.group.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "howmuch.group.image")
public record GroupImageProperties(String storageDir) {
    public GroupImageProperties {
        if (storageDir == null || storageDir.isBlank()) {
            storageDir = "./uploads/group-images";
        }
    }
}
