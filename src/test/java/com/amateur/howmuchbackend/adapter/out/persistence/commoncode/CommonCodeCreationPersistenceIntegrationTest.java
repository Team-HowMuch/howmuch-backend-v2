package com.amateur.howmuchbackend.adapter.out.persistence.commoncode;

import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.commoncode.service.CommonCodeAdminService;
import com.amateur.howmuchbackend.application.port.in.commoncode.command.CreateCommonCodeGroupCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.commoncode.LoadCommonCodeGroupPort;
import com.amateur.howmuchbackend.application.port.out.user.LoadUserPort;
import com.amateur.howmuchbackend.common.exception.ApplicationException;
import com.amateur.howmuchbackend.domain.commoncode.CommonCode;
import com.amateur.howmuchbackend.domain.commoncode.CommonCodeGroup;
import com.amateur.howmuchbackend.domain.commoncode.status.CommonCodeStatusCode;
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
class CommonCodeCreationPersistenceIntegrationTest {
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
        groupCode = "RACE_" + UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from common_code_detail where group_code = ?", groupCode);
        jdbc.update("delete from common_code_group where group_code = ?", groupCode);
    }

    @ParameterizedTest(name = "미존재 검사 이후 선행 커밋: 상세 포함={0}")
    @ValueSource(booleans = {false, true})
    void commonCodeCreation_staleAbsenceRead_rejectedAfterCommit(boolean withDetails) {
        var snapshot = new AtomicReference<CommonCodeGroup>();
        var delayedLoad = mock(LoadCommonCodeGroupPort.class);
        when(delayedLoad.findByGroupCode(groupCode)).thenAnswer(call -> {
            var absent = adapter.findByGroupCode(groupCode);
            assertThat(absent).isEmpty();
            // B는 행이 없다고 조회했다. 이제 A가 별도 연결에서 같은 부재 상태를 읽고 커밋한다.
            separateTx.executeWithoutResult(status -> {
                service(adapter).create(command("winner"));
                if (withDetails) adapter.update(group("winner", List.of(detail("KEEP", "original"))));
            });
            snapshot.set(separateTx.execute(status -> adapter.findByGroupCode(groupCode).orElseThrow()));
            return absent;
        });

        assertConflict(() -> tx.execute(status -> service(delayedLoad).create(command("loser"))));

        assertThat(read()).isEqualTo(snapshot.get());
    }

    @Test
    void commonCodeCreation_concurrentInserts_haveOneWinner() throws Exception {
        var barrier = new CyclicBarrier(2);
        var synchronizedLoad = mock(LoadCommonCodeGroupPort.class);
        when(synchronizedLoad.findByGroupCode(groupCode)).thenAnswer(call -> {
            var absent = adapter.findByGroupCode(groupCode);
            assertThat(absent).isEmpty();
            barrier.await(10, TimeUnit.SECONDS);
            return absent;
        });
        var service = service(synchronizedLoad);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> createOutcome(service, "first"));
            var second = executor.submit(() -> createOutcome(service, "second"));
            var outcomes = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("created", "conflict");
        }
        assertThat(read().groupName()).isIn("first", "second");
        assertThat(jdbc.queryForObject("select count(*) from common_code_group where group_code = ?",
                Long.class, groupCode)).isEqualTo(1L);
    }

    @Test
    void duplicateCreateCannotReplaceOrDeactivateDetails() {
        tx.executeWithoutResult(status -> adapter.create(group("winner", List.of(detail("KEEP", "original")))));
        var before = read();

        assertConflict(() -> tx.execute(status -> adapter.create(group("loser",
                List.of(detail("KEEP", "overwritten"), detail("NEW", "unexpected"))))));

        assertThat(read()).isEqualTo(before);
    }

    @Test
    void updateStillChangesDetailsAndDeactivatesRemovedOnes() {
        tx.executeWithoutResult(status -> adapter.create(group("before",
                List.of(detail("KEEP", "original"), detail("REMOVE", "removed")))));
        tx.executeWithoutResult(status -> adapter.update(group("after", List.of(detail("KEEP", "changed")))));

        var after = read();
        assertThat(after.groupName()).isEqualTo("after");
        assertThat(after.details()).filteredOn(d -> d.code().equals("KEEP"))
                .singleElement().isEqualTo(detail("KEEP", "changed"));
        assertThat(after.details()).filteredOn(d -> d.code().equals("REMOVE"))
                .singleElement().satisfies(d -> assertThat(d.active()).isFalse());
    }

    @Test
    void missingUpdateDoesNotCreateGroup() {
        assertThatThrownBy(() -> tx.execute(status -> adapter.update(group("missing", List.of()))))
                .isInstanceOfSatisfying(ApplicationException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(CommonCodeStatusCode.COMMON_CODE_GROUP_NOT_FOUND));
        Optional<CommonCodeGroup> after = tx.execute(status -> adapter.findByGroupCode(groupCode));
        assertThat(after).isEmpty();
    }

    private String createOutcome(CommonCodeAdminService service, String name) {
        try {
            tx.execute(status -> service.create(command(name)));
            return "created";
        } catch (ApplicationException exception) {
            assertThat(exception.getStatusCode()).isEqualTo(CommonCodeStatusCode.COMMON_CODE_GROUP_ALREADY_EXISTS);
            return "conflict";
        }
    }

    private CommonCodeAdminService service(LoadCommonCodeGroupPort load) {
        var actor = mock(CurrentActorPort.class);
        var users = mock(LoadUserPort.class);
        var admin = mock(User.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(admin.role()).thenReturn(UserRole.ADMIN);
        return new CommonCodeAdminService(actor, users, load, adapter);
    }

    private CreateCommonCodeGroupCommand command(String name) {
        return new CreateCommonCodeGroupCommand(groupCode, name, name, name, name, name, true);
    }

    private CommonCodeGroup group(String name, List<CommonCode> details) {
        return new CommonCodeGroup(groupCode, name, name, name, name, name, true, 0L, details);
    }

    private CommonCode detail(String code, String name) {
        return new CommonCode(groupCode, code, name, name, 1, name, name, name, true);
    }

    private CommonCodeGroup read() {
        return tx.execute(status -> adapter.findByGroupCode(groupCode).orElseThrow());
    }

    private void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApplicationException.class, exception -> {
            assertThat(exception.getStatusCode()).isEqualTo(CommonCodeStatusCode.COMMON_CODE_GROUP_ALREADY_EXISTS);
            assertThat(exception.getCause()).isNotNull();
        });
    }
}
