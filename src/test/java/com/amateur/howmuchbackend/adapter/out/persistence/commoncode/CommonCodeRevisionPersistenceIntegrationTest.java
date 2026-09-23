package com.amateur.howmuchbackend.adapter.out.persistence.commoncode;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.commoncode.service.CommonCodeAdminService;
import com.amateur.howmuchbackend.application.port.in.commoncode.command.CommonCodeDetailCommand;
import com.amateur.howmuchbackend.application.port.in.commoncode.command.UpdateCommonCodeGroupCommand;
import com.amateur.howmuchbackend.application.port.in.commoncode.result.CommonCodeGroupResult;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodeGroupPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.common.exception.status.CommonStatusCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import com.amateur.howmuchbackend.domain.user.User;
import com.amateur.howmuchbackend.domain.user.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class CommonCodeRevisionPersistenceIntegrationTest {
    @Autowired private CommonCodePersistenceAdapter adapter;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private TransactionTemplate tx;
    private TransactionTemplate separateTx;
    private String groupCode;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        separateTx = new TransactionTemplate(transactionManager);
        separateTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        groupCode = "REV_" + UUID.randomUUID().toString().replace("-", "");
        tx.executeWithoutResult(status -> adapter.create(new CommonCodeGroup(
                groupCode, "group", null, null, null, null, true, 0L,
                List.of(detail("KEEP", "original", true), detail("REMOVE", "original", true)))));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from common_code_detail where group_code = ?", groupCode);
        jdbc.update("delete from common_code_group where group_code = ?", groupCode);
    }

    @ParameterizedTest(name = "상세 변경 및 오래된 요청 거절: {0}")
    @ValueSource(strings = {"add", "edit", "deactivate", "omit"})
    void commonCodeRevision_detailEdit_incrementsAndRejectsStale(String operation) {
        var before = read();
        List<CommonCode> details = switch (operation) {
            case "add" -> List.of(before.details().getFirst(), before.details().getLast(), detail("NEW", "added", true));
            case "edit" -> List.of(detail("KEEP", "changed", true), detail("REMOVE", "original", true));
            case "deactivate" -> List.of(detail("KEEP", "original", false), detail("REMOVE", "original", true));
            default -> List.of(detail("KEEP", "original", true));
        };

        var response = tx.execute(status -> service(adapter).update(groupCode, command(before, details)));
        var committed = read();
        assertThat(committed.revision()).isGreaterThan(before.revision());
        assertThat(response.revision()).isEqualTo(committed.revision());
        assertThat(committed.groupName()).isEqualTo(before.groupName());
        assertThat(committed.details()).containsAll(details);
        if (operation.equals("omit")) {
            assertThat(committed.details()).contains(detail("REMOVE", "original", false));
        }

        assertConflict(() -> tx.execute(status -> service(adapter).update(groupCode,
                command(before, List.of(detail("KEEP", "stale", true))))));
        assertThat(read()).isEqualTo(committed);
    }

    @Test
    void concurrentDetailWritersHaveOneWinner() throws Exception {
        var before = read();
        var barrier = new CyclicBarrier(2);
        var synchronizedLoad = mock(LoadCommonCodeGroupPort.class);
        when(synchronizedLoad.findByGroupCode(groupCode)).thenAnswer(call -> {
            var snapshot = adapter.findByGroupCode(groupCode);
            assertThat(snapshot.orElseThrow().revision()).isEqualTo(before.revision());
            barrier.await(10, TimeUnit.SECONDS);
            return snapshot;
        });
        var service = service(synchronizedLoad);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> updateOutcome(service, before, "first"));
            var second = executor.submit(() -> updateOutcome(service, before, "second"));
            var outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(o -> o.result() == null).hasSize(1);
            var winner = outcomes.stream().filter(o -> o.result() != null).findFirst().orElseThrow();
            var committed = read();
            assertThat(committed.revision()).isEqualTo(winner.result().revision()).isGreaterThan(before.revision());
            assertThat(committed.details()).containsExactlyInAnyOrder(
                    detail("KEEP", winner.name(), true), detail("REMOVE", "original", false),
                    detail(winner.name().toUpperCase(), winner.name(), true));
        }
    }

    @Test
    void rejectsWriterWhoseManagedGroupBecameStaleAfterRead() {
        var before = read();
        var winner = new AtomicReference<CommonCodeGroup>();
        var delayedLoad = mock(LoadCommonCodeGroupPort.class);
        when(delayedLoad.findByGroupCode(groupCode)).thenAnswer(call -> {
            var snapshot = adapter.findByGroupCode(groupCode);
            separateTx.executeWithoutResult(status -> service(adapter).update(groupCode,
                    command(before, List.of(detail("KEEP", "winner", true)))));
            winner.set(separateTx.execute(status -> adapter.findByGroupCode(groupCode).orElseThrow()));
            return snapshot;
        });

        assertConflict(() -> tx.execute(status -> service(delayedLoad).update(groupCode,
                command(before, List.of(detail("KEEP", "loser", true), detail("NEW", "loser", true))))));

        assertThat(read()).isEqualTo(winner.get());
    }

    @Test
    void adapterRejectsStaleDetachedRevision() {
        var before = read();
        tx.executeWithoutResult(status -> adapter.update(withDetails(before, List.of(detail("KEEP", "winner", true)))));
        var winner = read();

        assertConflict(() -> tx.execute(status -> adapter.update(withDetails(before, List.of()))));

        assertThat(read()).isEqualTo(winner);
    }

    @Test
    void rollbackRestoresRevisionAndAllDetails() {
        var before = read();
        tx.executeWithoutResult(status -> {
            var updated = adapter.update(withDetails(before, List.of(detail("NEW", "rolled back", true))));
            assertThat(updated.revision()).isGreaterThan(before.revision());
            status.setRollbackOnly();
        });
        assertThat(read()).isEqualTo(before);
    }

    @Test
    void metadataAndDetailEditReturnsCommittedRevisionAndAllowsNextEdit() {
        var before = read();
        var response = tx.execute(status -> adapter.update(before.update("renamed", "description",
                "attr1", null, null, false, List.of(detail("KEEP", "changed", true)))));
        assertThat(response).isEqualTo(read());
        var next = tx.execute(status -> adapter.update(withDetails(response, List.of(detail("KEEP", "next", true)))));
        assertThat(next).isEqualTo(read());
        assertThat(next.revision()).isGreaterThan(response.revision());
    }

    private Outcome updateOutcome(CommonCodeAdminService service, CommonCodeGroup before, String name) {
        try {
            return new Outcome(name, tx.execute(status -> service.update(groupCode,
                    command(before, List.of(detail("KEEP", name, true), detail(name.toUpperCase(), name, true))))));
        } catch (ApplicationException exception) {
            assertThat(exception.getStatusCode()).isEqualTo(CommonStatusCode.CONCURRENT_UPDATE);
            return new Outcome(name, null);
        }
    }

    private record Outcome(String name, CommonCodeGroupResult result) {}

    private CommonCodeAdminService service(LoadCommonCodeGroupPort load) {
        var actor = mock(CurrentActorPort.class);
        var users = mock(LoadUserPort.class);
        var admin = mock(User.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(admin.role()).thenReturn(UserRole.ADMIN);
        return new CommonCodeAdminService(actor, users, load, adapter);
    }

    private UpdateCommonCodeGroupCommand command(CommonCodeGroup before, List<CommonCode> details) {
        return new UpdateCommonCodeGroupCommand(before.revision(), before.groupName(), before.description(),
                before.attr1Name(), before.attr2Name(), before.attr3Name(), before.active(), details.stream()
                .map(d -> new CommonCodeDetailCommand(d.code(), d.name(), d.description(), d.sortOrder(),
                        d.attr1(), d.attr2(), d.attr3(), d.active())).toList());
    }

    private CommonCodeGroup withDetails(CommonCodeGroup before, List<CommonCode> details) {
        return before.update(before.groupName(), before.description(), before.attr1Name(), before.attr2Name(),
                before.attr3Name(), before.active(), details);
    }

    private CommonCode detail(String code, String name, boolean active) {
        return new CommonCode(groupCode, code, name, null, 1, null, null, null, active);
    }

    private CommonCodeGroup read() {
        return tx.execute(status -> adapter.findByGroupCode(groupCode).orElseThrow());
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApplicationException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(CommonStatusCode.CONCURRENT_UPDATE));
    }
}
