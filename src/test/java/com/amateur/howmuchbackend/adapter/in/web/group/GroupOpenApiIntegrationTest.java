package com.amateur.howmuchbackend.adapter.in.web.group;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("그룹 OpenAPI 통합")
class GroupOpenApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("그룹 13개 API의 경로, 요청 형식, 성공 응답과 bearer 인증을 공개한다")
    void documentsGroupApiContract() throws Exception {
        byte[] content = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        JsonNode document = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));

        List<OperationContract> operations = List.of(
                new OperationContract("/api/v2/groups", "post", "201"),
                new OperationContract("/api/v2/groups", "get", "200"),
                new OperationContract("/api/v2/groups/{groupId}", "get", "200"),
                new OperationContract("/api/v2/groups/{groupId}", "patch", "200"),
                new OperationContract("/api/v2/groups/{groupId}", "delete", "200"),
                new OperationContract("/api/v2/groups/{groupId}/image", "get", "200"),
                new OperationContract("/api/v2/groups/join", "post", "200"),
                new OperationContract("/api/v2/groups/{groupId}/members", "get", "200"),
                new OperationContract("/api/v2/groups/{groupId}/members", "post", "201"),
                new OperationContract(
                        "/api/v2/groups/{groupId}/members/{memberId}/avatar",
                        "get",
                        "200"
                ),
                new OperationContract("/api/v2/groups/{groupId}/owner", "put", "200"),
                new OperationContract("/api/v2/groups/{groupId}/members/me", "delete", "200"),
                new OperationContract(
                        "/api/v2/groups/{groupId}/members/{memberId}",
                        "delete",
                        "200"
                )
        );

        operations.forEach(contract -> {
            JsonNode operation = operation(document, contract.path(), contract.method());
            assertThat(operation.isMissingNode())
                    .as("%s %s operation", contract.method(), contract.path())
                    .isFalse();
            assertThat(operation.path("responses").has(contract.successResponse()))
                    .as("%s %s success response", contract.method(), contract.path())
                    .isTrue();
            assertThat(operation.path("security").toString())
                    .as("%s %s bearer security", contract.method(), contract.path())
                    .contains("bearerAuth");
        });

        assertRequestContent(document, "/api/v2/groups", "post", "multipart/form-data");
        assertRequestContent(
                document,
                "/api/v2/groups/{groupId}",
                "patch",
                "multipart/form-data"
        );
        assertRequestContent(document, "/api/v2/groups/join", "post", "application/json");
        assertRequestContent(
                document,
                "/api/v2/groups/{groupId}/members",
                "post",
                "application/json"
        );
        assertRequestContent(
                document,
                "/api/v2/groups/{groupId}/owner",
                "put",
                "application/json"
        );

        JsonNode securityScheme = document.path("components")
                .path("securitySchemes")
                .path("bearerAuth");
        assertThat(securityScheme.path("type").stringValue()).isEqualTo("http");
        assertThat(securityScheme.path("scheme").stringValue()).isEqualTo("bearer");
        assertThat(securityScheme.path("bearerFormat").stringValue()).isEqualTo("JWT");

        assertSchemaProperties(
                document,
                "GroupDetailWebResponse",
                "groupId",
                "name",
                "memberCount",
                "inviteCode",
                "inviteUrl",
                "myRole",
                "createdAt"
        );
        assertSchemaProperties(
                document,
                "GroupSummaryWebResponse",
                "groupId",
                "name",
                "imageUrl"
        );
        assertSchemaProperties(
                document,
                "GroupMemberWebResponse",
                "memberId",
                "publicId",
                "name",
                "role",
                "joinedAt",
                "avatarUrl"
        );
        assertSchemaProperties(document, "JoinGroupWebRequest", "inviteCode");
        assertSchemaProperties(document, "AddGroupMemberWebRequest", "publicId");
        assertSchemaProperties(
                document,
                "TransferGroupOwnershipWebRequest",
                "targetMemberId"
        );
    }

    private JsonNode operation(JsonNode document, String path, String method) {
        return document.path("paths").path(path).path(method);
    }

    private void assertRequestContent(
            JsonNode document,
            String path,
            String method,
            String mediaType
    ) {
        assertThat(operation(document, path, method)
                .path("requestBody")
                .path("content")
                .has(mediaType))
                .as("%s %s request content", method, path)
                .isTrue();
    }

    private void assertSchemaProperties(
            JsonNode document,
            String schemaName,
            String... propertyNames
    ) {
        JsonNode properties = document.path("components")
                .path("schemas")
                .path(schemaName)
                .path("properties");
        assertThat(properties.isObject()).as("%s schema", schemaName).isTrue();
        assertThat(propertyNames).allSatisfy(propertyName ->
                assertThat(properties.has(propertyName))
                        .as("%s.%s", schemaName, propertyName)
                        .isTrue()
        );
    }

    private record OperationContract(
            String path,
            String method,
            String successResponse
    ) {
    }
}
