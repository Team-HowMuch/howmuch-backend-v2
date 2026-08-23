package com.amateur.howmuchbackend.adapter.in.web.commoncode;

import com.amateur.howmuchbackend.application.port.in.commoncode.CreateCommonCodeGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.commoncode.GetCommonCodeGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.commoncode.SearchCommonCodeGroupsUseCase;
import com.amateur.howmuchbackend.application.port.in.commoncode.UpdateCommonCodeGroupUseCase;
import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeDetailResult;
import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeGroupResult;
import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeGroupSummaryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.http.MediaType;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("공통 코드 관리자 Web API")
class CommonCodeAdminControllerTest {
    @Mock
    private SearchCommonCodeGroupsUseCase searchUseCase;

    @Mock
    private GetCommonCodeGroupUseCase getUseCase;

    @Mock
    private CreateCommonCodeGroupUseCase createUseCase;

    @Mock
    private UpdateCommonCodeGroupUseCase updateUseCase;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new CommonCodeAdminController(
                searchUseCase,
                getUseCase,
                createUseCase,
                updateUseCase
        )).setValidator(validator).build();
    }

    @Test
    @DisplayName("그룹 검색 결과에 그룹 속성과 상세 개수를 반환한다")
    void searchesGroups() throws Exception {
        when(searchUseCase.search(any())).thenReturn(List.of(
                new CommonCodeGroupSummaryResult("BANK", "은행 코드", null, true, 1L, 2L)
        ));

        mockMvc.perform(get("/api/v2/admin/common-codes/groups").param("keyword", "은행"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].groupCode").value("BANK"))
                .andExpect(jsonPath("$.data[0].detailCount").value(2));
    }

    @Test
    @DisplayName("그룹 저장 요청을 Application Command로 전달한다")
    void updatesGroup() throws Exception {
        when(updateUseCase.update(eq("BANK"), any())).thenReturn(groupResult());

        mockMvc.perform(put("/api/v2/admin/common-codes/groups/BANK")
                        .contentType("application/json")
                        .content("""
                                {
                                  "revision": 1,
                                  "groupName": "은행 코드",
                                  "attr1Name": "아이콘 URL",
                                  "attr2Name": "홈페이지",
                                  "attr3Name": "추가 값",
                                  "active": true,
                                  "details": [{
                                    "code": "0020",
                                    "name": "우리은행",
                                    "sortOrder": 1,
                                    "attr1": "https://example.com/icon.svg",
                                    "attr2": "https://example.com",
                                    "attr3": "bank-woori",
                                    "active": true
                                  }]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attr1Name").value("아이콘 URL"))
                .andExpect(jsonPath("$.data.details[0].attr3").value("bank-woori"));

        verify(updateUseCase).update(eq("BANK"), any());
    }

    @Test
    @DisplayName("상세 목록의 null 원소를 400으로 거부한다")
    void rejectsNullDetailElement() throws Exception {
        mockMvc.perform(put("/api/v2/admin/common-codes/groups/BANK")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "revision": 1,
                                  "groupName": "은행 코드",
                                  "active": true,
                                  "details": [null]
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    private CommonCodeGroupResult groupResult() {
        return new CommonCodeGroupResult(
                "BANK",
                "은행 코드",
                null,
                "아이콘 URL",
                "홈페이지",
                "추가 값",
                true,
                2L,
                List.of(new CommonCodeDetailResult(
                        "0020",
                        "우리은행",
                        null,
                        1,
                        "https://example.com/icon.svg",
                        "https://example.com",
                        "bank-woori",
                        true
                ))
        );
    }
}
