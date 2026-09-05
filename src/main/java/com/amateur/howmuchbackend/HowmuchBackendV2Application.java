package com.amateur.howmuchbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.time.ZoneOffset;
import java.util.TimeZone;

@SpringBootApplication
public class HowmuchBackendV2Application {

    public static void main(String[] args) {
        // Initialize JDBC and framework defaults consistently with UTC LocalDateTime storage.
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneOffset.UTC));
        SpringApplication.run(HowmuchBackendV2Application.class, args);
    }

}
