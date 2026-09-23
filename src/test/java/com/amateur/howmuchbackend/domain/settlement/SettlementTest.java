package com.amateur.howmuchbackend.domain.settlement;

import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.settlement.status.SettlementStatusCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("정산 도메인 정책")
class SettlementTest {
    private static final Long CREATOR_ID = 1L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 21, 9, 0);

    @Nested
    @DisplayName("정산 생성 규칙")
    class CreationPolicy {
        @Test
        @DisplayName("사용자가 실수로 입력한 양끝 공백은 같은 제목으로 취급하기 위해 제거한다")
        void settlement_titleBoundaryWhitespace_trimmed() {
            Settlement settlement = Settlement.draft(CREATOR_ID, "  회식 정산  ", 10_000, CREATED_AT);

            assertThat(settlement.title()).isEqualTo("회식 정산");
            assertThat(settlement.status()).isEqualTo(SettlementStatus.DRAFT);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   "})
        @DisplayName("정산을 식별할 수 있도록 비어 있는 제목을 허용하지 않는다")
        void settlement_emptyTitle_rejected(String title) {
            assertThatThrownBy(() -> Settlement.draft(CREATOR_ID, title, 10_000, CREATED_AT))
                    .isInstanceOfSatisfying(ApplicationException.class, exception ->
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_TITLE));
        }

        @ParameterizedTest
        @ValueSource(longs = {0, -1})
        @DisplayName("실제 비용을 나눌 수 있도록 0원 이하 총액을 허용하지 않는다")
        void settlement_nonPositiveTotal_rejected(long amount) {
            assertThatThrownBy(() -> Settlement.draft(CREATOR_ID, "회식 정산", amount, CREATED_AT))
                    .isInstanceOfSatisfying(ApplicationException.class, exception ->
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_AMOUNT));
        }
    }

    @Nested
    @DisplayName("초안 수정 정책")
    class DraftUpdatePolicy {
        @Test
        @DisplayName("PATCH에서 전달하지 않은 필드는 기존 정산 내용을 유지한다")
        void settlementPatch_omittedFields_preserved() {
            Settlement draft = draft();

            Settlement titleUpdated = draft.updateDraft("최종 회식 정산", null, CREATED_AT.plusMinutes(1));
            Settlement amountUpdated = draft.updateDraft(null, 20_000L, CREATED_AT.plusMinutes(1));

            assertThat(titleUpdated.title()).isEqualTo("최종 회식 정산");
            assertThat(titleUpdated.totalAmount()).isEqualTo(10_000);
            assertThat(amountUpdated.title()).isEqualTo("회식 정산");
            assertThat(amountUpdated.totalAmount()).isEqualTo(20_000);
        }

        @Test
        @DisplayName("의미 없는 수정을 막기 위해 변경할 필드가 없는 PATCH를 거부한다")
        void settlementPatch_noChanges_rejected() {
            assertThatThrownBy(() -> draft().updateDraft(null, null, CREATED_AT.plusMinutes(1)))
                    .isInstanceOfSatisfying(ApplicationException.class, exception ->
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_UPDATE));
        }

        @Test
        @DisplayName("정산 요청 이후 참여 기준이 바뀌지 않도록 DRAFT에서만 기본 정보를 수정한다")
        void settlement_afterRequest_update_rejected() {
            Settlement requested = draft().request(CREATED_AT.plusMinutes(1));

            assertThatThrownBy(() -> requested.updateDraft("변경된 제목", null, CREATED_AT.plusMinutes(2)))
                    .isInstanceOfSatisfying(ApplicationException.class, exception ->
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(SettlementStatusCode.SETTLEMENT_NOT_EDITABLE));
        }
    }

    @Nested
    @DisplayName("수령 계좌 선택 정책")
    class ReceivingAccountPolicy {
        @Test
        @DisplayName("송금 안내에 사용할 수 있도록 초안에 수령 계좌를 선택한다")
        void settlement_draftReceivingAccount_selected() {
            Settlement selected = draft().selectReceivingAccount(7L, CREATED_AT.plusMinutes(1));

            assertThat(selected.receivingAccountId()).isEqualTo(7L);
            assertThat(selected.updatedAt()).isEqualTo(CREATED_AT.plusMinutes(1));
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(longs = {0, -1})
        @DisplayName("유효한 계좌를 참조하도록 비어 있거나 0 이하인 ID를 거부한다")
        void settlement_accountIdNonPositive_rejected(Long accountId) {
            assertThatThrownBy(() -> draft().selectReceivingAccount(
                    accountId, CREATED_AT.plusMinutes(1)
            )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                    assertThat(exception.getStatusCode()).isEqualTo(
                            SettlementStatusCode.INVALID_SETTLEMENT_RECEIVING_ACCOUNT_ID
                    ));
        }

        @Test
        @DisplayName("요청 참여자의 송금 대상이 바뀌지 않도록 초안 이후 선택을 거부한다")
        void rejectsSelectionAfterRequest() {
            Settlement requested = draft().request(CREATED_AT.plusMinutes(1));

            assertThatThrownBy(() -> requested.selectReceivingAccount(
                    7L, CREATED_AT.plusMinutes(2)
            )).isInstanceOfSatisfying(ApplicationException.class, exception ->
                    assertThat(exception.getStatusCode()).isEqualTo(
                            SettlementStatusCode.SETTLEMENT_NOT_EDITABLE
                    ));
        }
    }

    @Nested
    @DisplayName("상태 전이 정책")
    class StatusTransitionPolicy {
        @Test
        @DisplayName("입금 확인 절차를 보장하기 위해 정의된 순서대로만 완료 상태에 도달한다")
        void followsDepositConfirmationFlow() {
            Settlement requested = draft().request(CREATED_AT.plusMinutes(1));
            Settlement awaitingDeposit = requested.awaitDeposit(CREATED_AT.plusMinutes(2));
            Settlement completed = awaitingDeposit.complete(CREATED_AT.plusMinutes(3));

            assertThat(requested.status()).isEqualTo(SettlementStatus.REQUESTED);
            assertThat(awaitingDeposit.status()).isEqualTo(SettlementStatus.AWAITING_DEPOSIT);
            assertThat(completed.status()).isEqualTo(SettlementStatus.COMPLETED);
            assertThat(completed.updatedAt()).isEqualTo(CREATED_AT.plusMinutes(3));
        }

        @Test
        @DisplayName("입금 확인 단계를 건너뛰지 않도록 DRAFT에서 바로 완료하는 전이를 거부한다")
        void rejectsSkippedTransition() {
            assertThatThrownBy(() -> draft().complete(CREATED_AT.plusMinutes(1)))
                    .isInstanceOfSatisfying(ApplicationException.class, exception ->
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_STATUS_TRANSITION));
        }

        @Test
        @DisplayName("생성자가 진행 중인 정산을 중단하고 다시 참여자 입력 상태로 재개한다")
        void suspendsAndResumesRequestedSettlement() {
            Settlement requested = draft().request(CREATED_AT.plusMinutes(1));
            Settlement suspended = requested.suspendManually(CREATED_AT.plusMinutes(2));
            Settlement resumed = suspended.resume(CREATED_AT.plusMinutes(3));

            assertThat(suspended.status()).isEqualTo(SettlementStatus.SUSPENDED);
            assertThat(resumed.status()).isEqualTo(SettlementStatus.REQUESTED);
            assertThat(resumed.updatedAt()).isEqualTo(CREATED_AT.plusMinutes(3));
        }

        @Test
        @DisplayName("요청되지 않은 정산이나 완료된 정산은 수동 중단·재개하지 않는다")
        void rejectsManualStatusTransitionsOutsideAllowedStates() {
            assertThatThrownBy(() -> draft().suspendManually(CREATED_AT.plusMinutes(1)))
                    .isInstanceOfSatisfying(ApplicationException.class, exception ->
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_STATUS_TRANSITION));

            Settlement completed = draft()
                    .request(CREATED_AT.plusMinutes(1))
                    .awaitDeposit(CREATED_AT.plusMinutes(2))
                    .complete(CREATED_AT.plusMinutes(3));
            assertThatThrownBy(() -> completed.resume(CREATED_AT.plusMinutes(4)))
                    .isInstanceOfSatisfying(ApplicationException.class, exception ->
                            assertThat(exception.getStatusCode())
                                    .isEqualTo(SettlementStatusCode.INVALID_SETTLEMENT_STATUS_TRANSITION));
        }
    }

    private Settlement draft() {
        return Settlement.draft(CREATOR_ID, "회식 정산", 10_000, CREATED_AT);
    }
}
