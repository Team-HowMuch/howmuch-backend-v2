package com.amateur.howmuchbackend.adapter.in.web.recap;

import com.amateur.howmuchbackend.application.port.in.recap.*;
import com.amateur.howmuchbackend.application.port.in.recap.result.*;
import com.amateur.howmuchbackend.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class RecapControllerTest {
    @Mock GetMyRecapMonthsUseCase months;
    @Mock GetMyMonthlyRecapUseCase detail;
    @Mock GetMySharedRecapUseCase share;
    @Mock GetMyRecapCompanionAvatarUseCase avatar;
    private MockMvc mvc;
    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final LocalDateTime GENERATED = LocalDateTime.of(2026, 10, 1, 0, 0);
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new RecapController(months, detail, share, avatar))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test void emptyMonthsReturnsEmptyArray() throws Exception {
        when(months.getMonths()).thenReturn(new RecapMonthsResult(List.of()));
        mvc.perform(get("/api/v2/recaps")).andExpect(status().isOk()).andExpect(jsonPath("$.data.months").isEmpty());
    }
    @Test void privateDetailMapsNumericComparisonAndNullCompanion() throws Exception {
        when(detail.getRecap(MONTH)).thenReturn(new MonthlyRecapResult(MONTH, "본인 이름", GENERATED,
                1, 0, new RecapComparisonResult("NO_PREVIOUS", null, null, null), null));
        mvc.perform(get("/api/v2/recaps/2026-09")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerName").value("본인 이름"))
                .andExpect(jsonPath("$.data.completedSettlementCount").value(1))
                .andExpect(jsonPath("$.data.totalOwnAmount").value(0))
                .andExpect(jsonPath("$.data.comparison.status").value("NO_PREVIOUS"))
                .andExpect(jsonPath("$.data.companion").doesNotExist());
    }
    @Test void shareDoesNotExposeOwnerAndUsesAuthenticatedAvatarAddress() throws Exception {
        when(share.getShare(MONTH)).thenReturn(new SharedMonthlyRecapResult(MONTH, GENERATED, 2, 1000,
                new RecapComparisonResult("NO_PREVIOUS", null, null, null),
                new RecapCompanionResult("MEMBER", "public_user", true, 2, 1000)));
        mvc.perform(get("/api/v2/recaps/2026-09/share")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownerName").doesNotExist())
                .andExpect(jsonPath("$.data.companion.displayName").value("public_user"))
                .andExpect(jsonPath("$.data.companion.avatarUrl").value("/api/v2/recaps/2026-09/companion/avatar"))
                .andExpect(jsonPath("$.data.companion.identityKey").doesNotExist())
                .andExpect(jsonPath("$.data.companion.hasAvatar").doesNotExist());
    }
    @Test void avatarIsBinaryAndNotCachedAcrossAccounts() throws Exception {
        when(avatar.getCompanionAvatar(MONTH)).thenReturn(new RecapAvatarResult("image/png", new byte[]{1,2}));
        mvc.perform(get("/api/v2/recaps/2026-09/companion/avatar")).andExpect(status().isOk())
                .andExpect(content().contentType("image/png")).andExpect(content().bytes(new byte[]{1,2}))
                .andExpect(header().string("Cache-Control", "no-store"));
    }
    @ParameterizedTest @ValueSource(strings = {"0000-01", "2026-1", "2026-00", "2026-13", "10000-01", "-001-01", "2026-01-01"})
    void rejectsNonStrictMonthOnAllMonthlyEndpoints(String month) throws Exception {
        for (String suffix : List.of("", "/share", "/companion/avatar")) {
            mvc.perform(get("/api/v2/recaps/" + month + suffix)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_ARGUMENT"));
        }
        verifyNoInteractions(detail, share, avatar);
    }
    @ParameterizedTest @ValueSource(strings = {"0001-01", "9999-12"})
    void acceptsFullContractYearRange(String text) throws Exception {
        when(detail.getRecap(YearMonth.parse(text))).thenReturn(new MonthlyRecapResult(YearMonth.parse(text), "본인", GENERATED,
                1, 0, new RecapComparisonResult("NO_PREVIOUS", null, null, null), null));
        mvc.perform(get("/api/v2/recaps/" + text)).andExpect(status().isOk()).andExpect(jsonPath("$.data.yearMonth").value(text));
    }
}
