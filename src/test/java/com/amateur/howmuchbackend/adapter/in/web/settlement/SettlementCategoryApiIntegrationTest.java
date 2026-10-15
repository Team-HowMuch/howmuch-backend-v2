package com.amateur.howmuchbackend.adapter.in.web.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused")
@DisplayName("정산 카테고리 목록 API")
class SettlementCategoryApiIntegrationTest {
    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("로그인 없이 정산용 18개를 순서대로 주고 확장용 카테고리는 주지 않는다")
    void categories_publicSettlementUseInOrder() throws Exception {
        mockMvc.perform(get("/api/v2/settlement-categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(18)))
                .andExpect(jsonPath("$.data[0].code").value("FOOD"))
                .andExpect(jsonPath("$.data[0].icon").value("food"))
                .andExpect(jsonPath("$.data[1].code").value("CAFE_SNACK"))
                .andExpect(jsonPath("$.data[1].icon").value("cafe-snack"))
                .andExpect(jsonPath("$.data[17].code").value("UNCATEGORIZED"))
                .andExpect(jsonPath("$.data[*].code", not(hasItem("TRANSFER"))))
                .andExpect(jsonPath("$.data[*].code", not(hasItem("DEFERRED_PAYMENT"))));
    }
}
