package com.amateur.howmuchbackend.adapter.in.web.settlement;

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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 OpenAPI 통합")
class SettlementOpenApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("목록과 상세 응답의 필수 필드와 nullable 필드를 공개한다")
    void settlementApi_responseRequiredAndNullableFields_documented() throws Exception {
        byte[] content = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        JsonNode document = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));

        assertRequired(
                document,
                "SettlementListItemWebResponse",
                "settlementId", "status", "requestedAt", "completedAt", "totalAmount", "participantCount",
                "completedParticipantCount", "amountToPay", "requesterName", "groups"
        );
        assertRequired(
                document,
                "SettlementListGroupSummaryWebResponse",
                "groupId", "groupName", "groupImageUrl"
        );
        assertRequired(
                document,
                "SettlementDetailWebResponse",
                "settlementId", "title", "totalAmount", "allocationType", "status", "createdAt",
                "viewerRole", "creatorName", "bankName", "accountNumber", "institutionIconUrl",
                "paymentInfo", "paymentSummary",
                "reminderNextAvailableAt", "participants"
        );
        assertRequired(document, "PaymentInfo", "amount", "storeName", "paidAt");
        assertRequired(
                document,
                "SettlementReminderWebResponse",
                "remindedAt", "nextAvailableAt"
        );
        assertRequired(
                document,
                "SettlementBulkReminderWebResponse",
                "sentCount", "nextAvailableAt"
        );
        assertRequired(document, "SettlementShareLinkWebResponse", "url", "expiresAt");
        assertRequired(
                document,
                "ParticipantPayment",
                "participantId", "displayName", "linked", "expectedAmount",
                "confirmedAmount", "paymentStatus", "avatarUrl"
        );
        assertRequired(document, "RegisterPushTargetWebRequest", "firebaseInstallationId");
        assertNullable(
                document,
                "SettlementListItemWebResponse",
                "completedAt", "totalAmount", "participantCount", "completedParticipantCount", "amountToPay", "requesterName"
        );
        assertDescriptionContainsUtc(document, "SettlementListItemWebResponse", "completedAt");
        List<JsonNode> parameters = new ArrayList<>();
        document.path("paths").path("/api/v2/settlements").path("get").path("parameters")
                .forEach(parameters::add);
        assertThat(parameters).filteredOn(parameter -> "yearMonth".equals(parameter.path("name").stringValue()))
                .singleElement().satisfies(parameter -> {
                    assertThat(parameter.path("required").asBoolean()).isFalse();
                    assertThat(parameter.path("schema").path("type").toString()).contains("string");
                });
        assertNullable(document, "SettlementListGroupSummaryWebResponse", "groupImageUrl");
        assertNullable(
                document,
                "SettlementDetailWebResponse",
                "allocationType", "bankName", "accountNumber", "institutionIconUrl",
                "reminderNextAvailableAt"
        );
        assertNullable(document, "SettlementBulkReminderWebResponse", "nextAvailableAt");
        assertNullable(document, "ParticipantPayment", "avatarUrl");
        assertNullable(document, "PaymentInfo", "storeName", "paidAt");
        assertRequired(document, "SettlementLinkEntryWebResponse", "requestedAt");
        assertNullable(document, "SettlementLinkEntryWebResponse", "storeName", "paidAt");
        assertDescriptionContainsUtc(document, "PaymentInfo", "paidAt");
        assertDescriptionContainsUtc(document, "SettlementLinkEntryWebResponse", "paidAt");
        assertDescriptionContainsUtc(document, "SettlementLinkEntryWebResponse", "requestedAt");
        assertDescriptionContainsUtc(
                document,
                "SettlementDetailWebResponse",
                "reminderNextAvailableAt"
        );
        assertDescriptionContainsUtc(
                document,
                "SettlementBulkReminderWebResponse",
                "nextAvailableAt"
        );
    }

    private void assertRequired(JsonNode document, String schemaName, String... propertyNames) {
        JsonNode required = schema(document, schemaName).path("required");
        List<String> requiredNames = new ArrayList<>();
        required.forEach(item -> requiredNames.add(item.stringValue()));
        assertThat(requiredNames).containsAll(List.of(propertyNames));
    }

    private void assertNullable(JsonNode document, String schemaName, String... propertyNames) {
        JsonNode properties = schema(document, schemaName).path("properties");
        assertThat(propertyNames).allSatisfy(propertyName -> {
            JsonNode property = properties.path(propertyName);
            JsonNode nullableNode = property.path("nullable");
            boolean nullable = nullableNode.isBoolean() && nullableNode.booleanValue()
                    || property.path("type").toString().contains("null")
                    || property.path("oneOf").toString().contains("null");
            assertThat(nullable)
                    .as("%s.%s nullable schema: %s", schemaName, propertyName, property)
                    .isTrue();
        });
    }

    private void assertDescriptionContainsUtc(
            JsonNode document,
            String schemaName,
            String propertyName
    ) {
        String description = schema(document, schemaName)
                .path("properties")
                .path(propertyName)
                .path("description")
                .stringValue();
        assertThat(description).contains("UTC", "offset 없는 값");
    }

    private JsonNode schema(JsonNode document, String schemaName) {
        JsonNode schema = document.path("components").path("schemas").path(schemaName);
        assertThat(schema.isMissingNode()).as("%s schema", schemaName).isFalse();
        return schema;
    }
}
