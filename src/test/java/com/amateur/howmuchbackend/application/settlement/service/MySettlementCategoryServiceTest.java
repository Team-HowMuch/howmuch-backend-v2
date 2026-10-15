package com.amateur.howmuchbackend.application.settlement.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.settlement.command.ChangeMySettlementCategoryCommand;
import com.amateur.howmuchbackend.application.port.in.settlement.result.SettlementCategoryResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementParticipantPort;
import com.amateur.howmuchbackend.application.port.out.settlement.LoadSettlementPort;
import com.amateur.howmuchbackend.application.port.out.settlement.SaveMySettlementCategoryPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.Settlement;
import com.amateur.howmuchbackend.domain.settlement.SettlementParticipant;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("내 카테고리 수정")
class MySettlementCategoryServiceTest {
    private static final UUID SETTLEMENT_ID = UUID.randomUUID();
    private static final long CREATOR_ID = 1L;
    private static final long MEMBER_ID = 2L;
    private static final long OUTSIDER_ID = 3L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 10, 15, 3, 0);
    private static final SettlementCategoryResult FOOD = new SettlementCategoryResult("FOOD", "식비", null, "food");
    private static final SettlementCategoryResult UNCATEGORIZED =
            new SettlementCategoryResult("UNCATEGORIZED", "미분류", "자동 분류 실패 또는 미지정", "uncategorized");

    @Mock private CurrentActorPort currentActorPort;
    @Mock private LoadSettlementPort loadSettlementPort;
    @Mock private LoadSettlementParticipantPort loadSettlementParticipantPort;
    @Mock private SettlementCategoryCatalog catalog;
    @Mock private SaveMySettlementCategoryPort savePort;

    private MySettlementCategoryService service;

    @BeforeEach
    void setUp() {
        service = new MySettlementCategoryService(
                currentActorPort, loadSettlementPort, loadSettlementParticipantPort, catalog, savePort);
    }

    @Test
    @DisplayName("생성자는 참여자 목록을 보지 않고 바꾸고, 저장한 카테고리를 표시명·아이콘과 함께 돌려준다")
    void change_creator_savesAndReturnsCategory() {
        actor(CREATOR_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(catalog.byCode()).thenReturn(categories());
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);

        SettlementCategoryResult result = service.change(SETTLEMENT_ID, new ChangeMySettlementCategoryCommand("FOOD"));

        assertThat(result).isEqualTo(FOOD);
        verify(savePort).save(eq(SETTLEMENT_ID), eq(CREATOR_ID), eq("FOOD"),
                argThat(now -> !now.isBefore(before) && !now.isAfter(LocalDateTime.now(ZoneOffset.UTC))));
        verifyNoInteractions(loadSettlementParticipantPort);
    }

    @Test
    @DisplayName("회원으로 연결된 참여자는 자기 카테고리를 미분류(UNCATEGORIZED)로도 바꿀 수 있다")
    void change_linkedParticipant_allowsUncategorized() {
        actor(MEMBER_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(participants());
        when(catalog.byCode()).thenReturn(categories());

        SettlementCategoryResult result =
                service.change(SETTLEMENT_ID, new ChangeMySettlementCategoryCommand("UNCATEGORIZED"));

        assertThat(result).isEqualTo(UNCATEGORIZED);
        verify(savePort).save(eq(SETTLEMENT_ID), eq(MEMBER_ID), eq("UNCATEGORIZED"), any());
    }

    @Test
    @DisplayName("정산과 관계없는 회원은 코드가 틀려도 403이고, 카테고리 목록을 읽거나 저장하지 않는다")
    void change_outsider_rejectedBeforeCodeCheck() {
        actor(OUTSIDER_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(loadSettlementParticipantPort.findAllBySettlementId(SETTLEMENT_ID)).thenReturn(participants());

        assertThatThrownBy(() -> service.change(SETTLEMENT_ID, new ChangeMySettlementCategoryCommand("NOPE")))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.SETTLEMENT_ACCESS_DENIED));
        verifyNoInteractions(catalog, savePort);
    }

    @Test
    @DisplayName("없거나 삭제된 정산은 404이고 참여자·카테고리를 읽지 않는다")
    void change_missingSettlement_notFound() {
        actor(CREATOR_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.change(SETTLEMENT_ID, new ChangeMySettlementCategoryCommand("FOOD")))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_FOUND));
        verifyNoInteractions(loadSettlementParticipantPort, catalog, savePort);
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRANSFER", "NOT_A_CODE", "food"})
    @DisplayName("카테고리 목록에 없는 코드(확장용·없는 코드·소문자)는 400이고 저장하지 않는다")
    void change_unknownCode_rejected(String code) {
        actor(CREATOR_ID);
        when(loadSettlementPort.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement()));
        when(catalog.byCode()).thenReturn(categories());

        assertThatThrownBy(() -> service.change(SETTLEMENT_ID, new ChangeMySettlementCategoryCommand(code)))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode())
                                .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_CATEGORY));
        verifyNoInteractions(savePort);
    }

    private void actor(long userId) {
        when(currentActorPort.getCurrentActor()).thenReturn(new CurrentActor(userId));
    }

    private Settlement settlement() {
        return Settlement.draft(CREATOR_ID, "내 카테고리", 10_000, CREATED_AT);
    }

    private List<SettlementParticipant> participants() {
        return List.of(
                SettlementParticipant.registeredUser(SETTLEMENT_ID, CREATOR_ID, "생성자", 0, CREATED_AT),
                SettlementParticipant.registeredUser(SETTLEMENT_ID, MEMBER_ID, "참여자", 1, CREATED_AT),
                SettlementParticipant.unlinkedContact(SETTLEMENT_ID, "a".repeat(64), "연락처", 2, CREATED_AT));
    }

    private Map<String, SettlementCategoryResult> categories() {
        Map<String, SettlementCategoryResult> categories = new LinkedHashMap<>();
        categories.put("FOOD", FOOD);
        categories.put("UNCATEGORIZED", UNCATEGORIZED);
        return categories;
    }
}
