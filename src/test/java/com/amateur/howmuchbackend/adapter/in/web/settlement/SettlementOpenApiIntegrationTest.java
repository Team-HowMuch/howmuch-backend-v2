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
    @DisplayName("수동 중단의 입금 재조회와 409 오류를 공개한다")
    void settlementApi_suspendDepositRefresh_documented() throws Exception {
        byte[] content = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        JsonNode document = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));
        JsonNode operation = document.path("paths")
                .path("/api/v2/settlements/{settlementId}/suspend").path("post");

        assertThat(operation.path("description").stringValue())
                .contains("REQUESTED 또는 AWAITING_DEPOSIT", "CODEF 거래내역을 다시 조회",
                        "미귀속 양수 입금", "은행 앱에서 시작한 송금은 취소되지 않을 수 있습니다");
        assertThat(operation.path("responses").path("409").path("description").stringValue())
                .contains("SETTLEMENT_NOT_SUSPENDABLE", "INVALID_SETTLEMENT_STATUS_TRANSITION",
                        "CONCURRENT_UPDATE");
    }

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
                "settlementId", "status", "requestedAt", "endedAt", "completedAt", "totalAmount", "participantCount",
                "completedParticipantCount", "amountToPay", "receivableAmount", "requesterName", "groups"
        );
        assertRequired(
                document,
                "SettlementListGroupSummaryWebResponse",
                "groupId", "groupName", "groupImageUrl"
        );
        assertRequired(
                document,
                "SettlementDetailWebResponse",
                "settlementId", "title", "totalAmount", "allocationType", "status", "createdAt", "endedAt",
                "viewerRole", "creatorName", "bankName", "accountNumber", "institutionIconUrl",
                "paymentInfo", "paymentSummary",
                "reminderNextAvailableAt", "participants", "items"
        );
        assertRequired(document, "PaymentInfo", "amount", "storeName", "paidAt", "deliveryFee");
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
                "confirmedAmount", "paymentStatus", "submitted", "avatarUrl", "deliveryFeeShare"
        );
        for (String schemaName : List.of("SettlementExpenseWebResponse", "SettlementItemListWebResponse",
                "SettlementItemAllocationWebResponse", "CreateSettlementReceiptExpenseWebRequest")) {
            assertThat(schema(document, schemaName).path("properties").has("deliveryFee")).isTrue();
        }
        assertThat(schema(document, "SettlementItemAllocationWebResponse").path("properties").has("deliveryFeeShare"))
                .isTrue();
        assertRequired(document, "RegisterPushTargetWebRequest", "firebaseInstallationId");
        assertNullable(
                document,
                "SettlementListItemWebResponse",
                "endedAt", "completedAt", "totalAmount", "participantCount", "completedParticipantCount", "amountToPay",
                "receivableAmount", "requesterName"
        );
        assertDescriptionContainsUtc(document, "SettlementListItemWebResponse", "endedAt");
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
                "allocationType", "endedAt", "bankName", "accountNumber", "institutionIconUrl",
                "reminderNextAvailableAt"
        );
        assertDescriptionContainsUtc(document, "SettlementDetailWebResponse", "endedAt");
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

    @Test
    @DisplayName("내 카테고리를 nullable로, 카테고리 목록 API를 인증 없이 공개한다")
    void settlementCategoryApi_documented() throws Exception {
        byte[] content = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        JsonNode document = objectMapper.readTree(new String(content, StandardCharsets.UTF_8));

        for (String schemaName : List.of("SettlementListItemWebResponse", "SettlementDetailWebResponse")) {
            assertRequired(document, schemaName, "myCategory");
            assertNullableReference(document, schemaName, "myCategory", "SettlementMyCategoryWebResponse");
        }
        assertRequired(document, "SettlementMyCategoryWebResponse", "code", "name", "icon");
        assertRequired(document, "SettlementCategoryWebResponse", "code", "name", "description", "icon");
        assertNullable(document, "SettlementCategoryWebResponse", "description");
        assertThat(document.path("paths").path("/api/v2/settlement-categories").path("get").isMissingNode())
                .isFalse();

        JsonNode change = document.path("paths").path("/api/v2/settlements/{settlementId}/my-category").path("put");
        assertThat(change.isMissingNode()).isFalse();
        assertThat(change.path("security").toString()).contains("bearerAuth");
        assertThat(change.path("requestBody").toString()).contains("ChangeMySettlementCategoryWebRequest");
        assertThat(change.path("responses").path("200").toString()).contains("SettlementMyCategoryWebResponse");
        assertThat(change.path("responses").propertyNames()).contains("400", "401", "403", "404");
        assertRequired(document, "ChangeMySettlementCategoryWebRequest", "categoryCode");
    }

    /**
     * 참조 타입 속성이 실제로 null을 허용하는지 본다. OAS 3.0이면 nullable: true, 3.1이면 참조와 null 타입이
     * oneOf/anyOf의 서로 다른 갈래여야 한다. $ref 옆에 type: null만 붙은 형태는 "참조이면서 null"이라 null을 허용하지 않는다.
     */
    private void assertNullableReference(JsonNode document, String schemaName, String propertyName, String target) {
        JsonNode property = schema(document, schemaName).path("properties").path(propertyName);
        JsonNode nullableNode = property.path("nullable");
        boolean nullable = nullableNode.isBoolean() && nullableNode.booleanValue()
                && property.toString().contains("#/components/schemas/" + target + "\"");
        for (String keyword : List.of("oneOf", "anyOf")) {
            boolean reference = false;
            boolean nullType = false;
            for (JsonNode branch : property.path(keyword)) {
                reference |= branch.path("$ref").toString().endsWith("/" + target + "\"");
                nullType |= branch.path("type").toString().contains("null");
            }
            nullable |= reference && nullType;
        }
        assertThat(nullable).as("%s.%s nullable reference schema: %s", schemaName, propertyName, property).isTrue();
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
