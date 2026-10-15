package com.amateur.howmuchbackend.infra.recap;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "howmuch.recap.image")
public record RecapImageProperties(String storageDir) {
    public RecapImageProperties {
        if (storageDir == null || storageDir.isBlank()) storageDir = "./uploads/recap-images";
    }
}
