package com.amateur.howmuchbackend.application.treasurer.service;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.port.in.treasurer.AskTreasurerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.ReportTreasurerAnswerUseCase;
import com.amateur.howmuchbackend.application.port.in.treasurer.command.AskTreasurerCommand;
import com.amateur.howmuchbackend.application.port.in.treasurer.command.ReportTreasurerAnswerCommand;
import com.amateur.howmuchbackend.application.port.in.treasurer.result.TreasurerAnswerReportResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.GenerateTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.LoadTreasurerAnswerPort;
import com.amateur.howmuchbackend.application.port.out.treasurer.SaveTreasurerAnswerPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.treasurer.InquiryIntent;
import com.amateur.howmuchbackend.domain.treasurer.SavedTreasurerAnswer;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerGeneratedBy;
import com.amateur.howmuchbackend.domain.treasurer.TreasurerAnswerReportReason;
import com.amateur.howmuchbackend.domain.treasurer.status.TreasurerStatusCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "howmuch.auth.sms.discord.webhook-url=http://127.0.0.1:9/unused",
        "howmuch.settlement.notification-outbox.enabled=false",
        "howmuch.settlement.automatic-reminder.enabled=false",
        "howmuch.treasurer.answer-retention.enabled=false"
})
@DisplayName("AI 비서 답변 신고 영속 통합")
class TreasurerAnswerReportIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 15, 12, 0);

    @Autowired SaveTreasurerAnswerPort answers;
    @Autowired LoadTreasurerAnswerPort loader;
    @Autowired ReportTreasurerAnswerUseCase reports;
    @Autowired AskTreasurerUseCase ask;
    @Autowired PurgeExpiredTreasurerAnswerService purge;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CurrentActorPort actor;
    @MockitoBean Clock clock;
    @MockitoBean GenerateTreasurerAnswerPort generateTreasurerAnswerPort;

    Long owner;
    Long stranger;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW.toInstant(ZoneOffset.UTC));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        owner = insertUser();
        stranger = insertUser();
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(owner));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM treasurer_answer_reports WHERE reporter_user_id IN (?,?)", owner, stranger);
        jdbc.update("DELETE FROM treasurer_answers WHERE user_id IN (?,?)", owner, stranger);
        jdbc.update("DELETE FROM users WHERE id IN (?,?)", owner, stranger);
    }

    @Test
    @DisplayName("본인 답변을 신고하고 식별자와 접수 시각을 돌려받는다")
    void answerReport_ownAnswer_createdWithReceiptTime() {
        UUID answerId = saveAnswer(owner, NOW);

        TreasurerAnswerReportResult result = reports.report(
                new ReportTreasurerAnswerCommand(answerId, TreasurerAnswerReportReason.INACCURATE, "금액이 실제와 달라요."));

        assertThat(result.reportId()).isNotNull();
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject(
                "SELECT reason FROM treasurer_answer_reports WHERE id=?", String.class, result.reportId()))
                .isEqualTo("INACCURATE");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM treasurer_answer_reports WHERE id=?", String.class, result.reportId()))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject(
                "SELECT resolved_at FROM treasurer_answer_reports WHERE id=?",
                LocalDateTime.class, result.reportId()))
                .isNull();
    }

    @Test
    @DisplayName("남의 답변과 없는 답변은 구분 없이 같은 404다")
    void answerReport_otherUserOrMissingAnswer_sameNotFound() {
        UUID othersAnswer = saveAnswer(stranger, NOW);
        UUID missingAnswer = UUID.randomUUID();

        Throwable onOthers = catchThrowable(() ->
                reports.report(new ReportTreasurerAnswerCommand(othersAnswer, TreasurerAnswerReportReason.OFFENSIVE, null)));
        Throwable onMissing = catchThrowable(() ->
                reports.report(new ReportTreasurerAnswerCommand(missingAnswer, TreasurerAnswerReportReason.OFFENSIVE, null)));

        assertThat(onOthers).isInstanceOf(ApplicationException.class);
        assertThat(onMissing).isInstanceOf(ApplicationException.class);
        assertThat(((ApplicationException) onOthers).getStatusCode())
                .isEqualTo(TreasurerStatusCode.TREASURER_ANSWER_NOT_FOUND);
        // 존재 여부가 새면 안 되므로 메시지까지 같아야 한다.
        assertThat(onOthers.getMessage()).isEqualTo(onMissing.getMessage());
        assertThat(countReports(othersAnswer)).isZero();
    }

    @Test
    @DisplayName("같은 답변을 두 번 신고하면 409다")
    void rejectsDuplicateReport() {
        UUID answerId = saveAnswer(owner, NOW);
        reports.report(new ReportTreasurerAnswerCommand(answerId, TreasurerAnswerReportReason.OTHER, null));

        assertThatThrownBy(() -> reports.report(new ReportTreasurerAnswerCommand(answerId, TreasurerAnswerReportReason.PRIVACY, null)))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(TreasurerStatusCode.TREASURER_ANSWER_ALREADY_REPORTED);
        assertThat(countReports(answerId)).isOne();
    }

    @Test
    @DisplayName("동시에 두 번 신고해도 한 건만 접수된다")
    void concurrentReportsProduceExactlyOne() throws Exception {
        UUID answerId = saveAnswer(owner, NOW);

        List<Throwable> outcomes = concurrent(
                () -> catchThrowable(() -> reports.report(new ReportTreasurerAnswerCommand(answerId, TreasurerAnswerReportReason.OTHER, null))),
                () -> catchThrowable(() -> reports.report(new ReportTreasurerAnswerCommand(answerId, TreasurerAnswerReportReason.OTHER, null))));

        assertThat(outcomes).filteredOn(Objects::isNull).hasSize(1);
        assertThat(countReports(answerId)).isOne();
        // 진 쪽이 무엇을 던졌는지까지 봐야 한다. 여기를 보지 않으면 ON CONFLICT를 지워
        // 진 쪽이 500을 받게 되어도 이 테스트는 그대로 통과한다.
        Throwable loser = outcomes.stream().filter(Objects::nonNull).findFirst().orElseThrow();
        assertThat(loser).isInstanceOf(ApplicationException.class);
        assertThat(((ApplicationException) loser).getStatusCode())
                .isEqualTo(TreasurerStatusCode.TREASURER_ANSWER_ALREADY_REPORTED);
    }

    @Test
    @DisplayName("응용 계층을 건너뛰어도 스키마가 남의 답변 신고를 거부한다")
    void schemaRejectsReportOnSomeoneElsesAnswer() {
        UUID othersAnswer = saveAnswer(stranger, NOW);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO treasurer_answer_reports
                    (id,answer_id,reporter_user_id,reason,detail,status,created_at,resolved_at)
                VALUES (?,?,?,'OTHER',NULL,'OPEN',?,NULL)
                """, UUID.randomUUID(), othersAnswer, owner, NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("보관 기간이 지난 답변은 정리되고 신고된 답변은 남는다")
    void purgesExpiredAnswersButKeepsReportedOnes() {
        UUID expiredAndReported = saveAnswer(owner, NOW.minusDays(99));
        UUID expired = saveAnswer(owner, NOW.minusDays(31));
        UUID recent = saveAnswer(owner, NOW.minusDays(1));
        reports.report(new ReportTreasurerAnswerCommand(expiredAndReported, TreasurerAnswerReportReason.INACCURATE, null));

        int deleted = purge.purge(Duration.ofDays(30), 500);

        assertThat(deleted).isOne();
        assertThat(loader.findOwned(expired, owner)).isEmpty();
        assertThat(loader.findOwned(recent, owner)).isPresent();
        assertThat(loader.findOwned(expiredAndReported, owner)).isPresent();
    }

    @Test
    @DisplayName("정리는 배치 크기만큼만 지우고 남은 것은 다음 주기가 이어서 지운다")
    void purgesInBatches() {
        for (int i = 0; i < 5; i++) {
            saveAnswer(owner, NOW.minusDays(31 + i));
        }

        assertThat(purge.purge(Duration.ofDays(30), 2)).isEqualTo(2);
        assertThat(purge.purge(Duration.ofDays(30), 2)).isEqualTo(2);
        assertThat(purge.purge(Duration.ofDays(30), 2)).isOne();
        assertThat(purge.purge(Duration.ofDays(30), 2)).isZero();
    }

    @Test
    @DisplayName("정리된 답변을 신고하면 404다")
    void reportingPurgedAnswerIsNotFound() {
        UUID expired = saveAnswer(owner, NOW.minusDays(31));
        purge.purge(Duration.ofDays(30), 500);

        assertThatThrownBy(() -> reports.report(new ReportTreasurerAnswerCommand(expired, TreasurerAnswerReportReason.OTHER, null)))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(TreasurerStatusCode.TREASURER_ANSWER_NOT_FOUND);
    }

    @Test
    @DisplayName("조회 직후 답변이 정리되어도 500이 아니라 404다")
    void mapsForeignKeyViolationToNotFound() {
        UUID expired = saveAnswer(owner, NOW.minusDays(31));
        // 응용 계층 조회는 통과했는데 저장 직전에 정리가 지나간 상황을 만든다.
        jdbc.update("DELETE FROM treasurer_answers WHERE id=?", expired);

        assertThatThrownBy(() -> reports.report(
                new ReportTreasurerAnswerCommand(expired, TreasurerAnswerReportReason.OTHER, null)))
                .isInstanceOf(ApplicationException.class)
                .extracting(e -> ((ApplicationException) e).getStatusCode())
                .isEqualTo(TreasurerStatusCode.TREASURER_ANSWER_NOT_FOUND);
    }

    @Test
    @DisplayName("밀린 분량은 한 주기 안에서 배치를 이어 붙여 정리한다")
    void drainsBacklogWithinOneCycle() {
        for (int i = 0; i < 7; i++) {
            saveAnswer(owner, NOW.minusDays(31 + i));
        }

        // 배치 하나만 도는 구현이면 2건에서 멈춘다.
        assertThat(purge.purgeAll(Duration.ofDays(30), 2, 10)).isEqualTo(7);
        assertThat(purge.purgeAll(Duration.ofDays(30), 2, 10)).isZero();
    }

    @Test
    @DisplayName("한 주기에 도는 배치 수를 넘기면 남겨 두고 다음 주기로 넘긴다")
    void stopsAfterMaxBatches() {
        for (int i = 0; i < 7; i++) {
            saveAnswer(owner, NOW.minusDays(31 + i));
        }

        assertThat(purge.purgeAll(Duration.ofDays(30), 2, 2)).isEqualTo(4);
        assertThat(purge.purgeAll(Duration.ofDays(30), 2, 10)).isEqualTo(3);
    }

    @Test
    @DisplayName("질문 흐름은 LLM을 부르는 동안 트랜잭션을 쥐지 않는다")
    void doesNotHoldATransactionWhileCallingTheLlm() {
        AtomicBoolean transactionWasActive = new AtomicBoolean(true);
        when(generateTreasurerAnswerPort.generate(any())).thenAnswer(invocation -> {
            // 여기서 트랜잭션이 살아 있으면 커넥션 하나를 LLM 호출 내내 쥐고 있다는 뜻이다.
            // 그 상태로 저장이 두 번째 커넥션을 요구하면, 동시 질문이 풀 크기만큼 들어왔을 때
            // 모두가 두 번째 커넥션을 기다려 아무도 진행하지 못한다.
            transactionWasActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            return Optional.empty();
        });

        var result = ask.ask(new AskTreasurerCommand("이번 달 보낼 정산 얼마야"));

        assertThat(transactionWasActive).isFalse();
        // 트랜잭션을 걷어내도 저장은 그대로 되어야 한다.
        assertThat(result.answerId()).isNotNull();
        assertThat(loader.findOwned(result.answerId(), owner)).isPresent();
    }

    @Test
    @DisplayName("공백만 있는 상세는 저장하지 않는다")
    void storesBlankDetailAsAbsent() {
        UUID answerId = saveAnswer(owner, NOW);

        TreasurerAnswerReportResult result = reports.report(
                new ReportTreasurerAnswerCommand(answerId, TreasurerAnswerReportReason.OTHER, "   "));

        assertThat(jdbc.queryForObject(
                "SELECT detail FROM treasurer_answer_reports WHERE id=?", String.class, result.reportId()))
                .isNull();
    }

    private UUID saveAnswer(Long userId, LocalDateTime createdAt) {
        SavedTreasurerAnswer answer = SavedTreasurerAnswer.of(
                userId,
                "이번 달 보낼 정산 얼마야",
                "이번 달 기준으로 조회했어요. 보낼 정산은 1건, 총 30,000원이 남아 있어요.",
                InquiryIntent.SEND_SETTLEMENTS,
                TreasurerAnswerGeneratedBy.SERVER,
                createdAt
        );
        answers.save(answer);
        return answer.id();
    }

    private int countReports(UUID answerId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM treasurer_answer_reports WHERE answer_id=?", Integer.class, answerId);
        return count == null ? 0 : count;
    }

    private Long insertUser() {
        String unique = UUID.randomUUID().toString();
        return jdbc.queryForObject("""
                INSERT INTO users(identity_key,name,public_id,phone_number,pin_hash,phone_verified,role,active,created_at,updated_at)
                VALUES (?,'신고 테스트',?,?,'unused',true,'USER',true,?,?) RETURNING id
                """, Long.class, unique, "r_" + unique.replace("-", "").substring(0, 10),
                unique.substring(0, 20), NOW, NOW);
    }

    private <T> List<T> concurrent(Callable<T> first, Callable<T> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2)) {
            var latch = new CountDownLatch(1);
            var a = executor.submit(() -> { latch.await(); return first.call(); });
            var b = executor.submit(() -> { latch.await(); return second.call(); });
            latch.countDown();
            return Arrays.asList(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }
}
