package com.amateur.howmuchbackend.infra.swagger.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

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
                        .version("2.0.0"));
        }

        /**
         * {@code @Schema(nullable = true)}를 붙인 객체 타입 속성은 OAS 3.1에서 {@code $ref} 옆에 {@code type: null}이 붙어
         * "참조이면서 null"이라는, 실제로는 null을 허용하지 않는 스키마가 된다. 참조와 null을 anyOf 갈래로 나눠 바로잡는다.
         */
        @Bean
        public OpenApiCustomizer nullableReferenceCustomizer() {
                return openApi -> {
                        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                                return;
                        }
                        openApi.getComponents().getSchemas().values().forEach(schema -> {
                                @SuppressWarnings("unchecked")
                                Map<String, Schema> properties = schema.getProperties();
                                if (properties != null) {
                                        properties.replaceAll((name, property) -> nullableReference(property));
                                }
                        });
                };
        }

        @SuppressWarnings({"rawtypes", "unchecked"})
        static Schema nullableReference(Schema property) {
                if (property == null || property.get$ref() == null) {
                        return property;
                }
                boolean nullable = Boolean.TRUE.equals(property.getNullable())
                        || (property.getTypes() != null && property.getTypes().contains("null"))
                        || "null".equals(property.getType());
                if (!nullable) {
                        return property;
                }
                Schema reference = new Schema().$ref(property.get$ref());
                Schema nullType = new Schema();
                nullType.addType("null");
                Schema result = new Schema().anyOf(List.of(reference, nullType)).description(property.getDescription());
                if (property.getSpecVersion() != null) {
                        reference.setSpecVersion(property.getSpecVersion());
                        nullType.setSpecVersion(property.getSpecVersion());
                        result.setSpecVersion(property.getSpecVersion());
                }
                return result;
        }
}
