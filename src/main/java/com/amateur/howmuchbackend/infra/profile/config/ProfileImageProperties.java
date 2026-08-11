package com.amateur.howmuchbackend.infra.profile.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "howmuch.profile.image")
public record ProfileImageProperties(String storageDir) {
    public ProfileImageProperties {
        if (storageDir == null || storageDir.isBlank()) {
            storageDir = "./uploads/profile-images";
        }
    }
}
