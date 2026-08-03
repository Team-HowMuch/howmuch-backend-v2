package com.amateur.howmuchbackend.infra.swagger.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "HowMuch API",
                description = "HowMuch Backend v2 API",
                version = "v2"
        )
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT"
)
public class SwaggerConfig {
        @Bean
        public OpenAPI api() {
        return new OpenAPI()
                .info(new io.swagger.v3.oas.models.info.Info()
                        .title("HowMuch API")
                        .description("얼마써 API Documentation")
                        .version("2.0.0"))
                .addServersItem(new Server()
                        .url("https://dev.howmuch.wars.p-e.kr")
                        .description("Development"))
                .addServersItem(new Server()
                        .url("http://localhost:8080")
                        .description("Local"))
                .addServersItem(new Server()
                        .url("http://43.201.250.169:8080")
                        .description("Development2"))
                .addSecurityItem(new SecurityRequirement()
                        .addList("Authorization"))
                .components(new Components()
                        .addSecuritySchemes("Authorization", new io.swagger.v3.oas.models.security.SecurityScheme()
                                .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP)
                                .scheme("Bearer")
                                .bearerFormat("Authorization")
                                .in(io.swagger.v3.oas.models.security.SecurityScheme.In.HEADER)
                                .name(HttpHeaders.AUTHORIZATION)
                        ));
        }
}
