package com.amateur.howmuchbackend.application.image;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.amateur.howmuchbackend.application.auth.model.CurrentActor;
import com.amateur.howmuchbackend.application.group.service.GroupImageTransactionSupport;
import com.amateur.howmuchbackend.application.port.in.user.command.UpdateMyProfileCommand;
import com.amateur.howmuchbackend.application.port.out.auth.CurrentActorPort;
import com.amateur.howmuchbackend.application.port.out.group.GroupImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.user.*;
import com.amateur.howmuchbackend.application.user.policy.*;
import com.amateur.howmuchbackend.application.user.service.UpdateMyProfileService;
import com.amateur.howmuchbackend.domain.user.User;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;

import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImageCleanupTransactionTest {
    private static final String OLD = "00000000-0000-0000-0000-000000000001.jpg";
    private static final String NEW = "00000000-0000-0000-0000-000000000002.jpg";

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void imageCleanup_postCommitFailure_doesNotFailAndIsLogged(boolean group) {
        var fixture = fixture(group, false);
        fixture.failDelete.accept(OLD);
        var logger = (Logger) LoggerFactory.getLogger(group
                ? GroupImageTransactionSupport.class : UpdateMyProfileService.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        var manager = new TestTransactionManager();
        try {
            assertThat(new TransactionTemplate(manager).<String>execute(status -> {
                fixture.update.run();
                fixture.verifyNotDeleted.accept(OLD);
                return "success";
            })).isEqualTo("success");
            assertThat(manager.committed).isTrue();
            fixture.verifyDeleted.accept(OLD);
            fixture.verifyNotDeleted.accept(NEW);
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getKeyValuePairs()).anySatisfy(pair -> {
                    assertThat(pair.key).isEqualTo("fileName");
                    assertThat(pair.value).isEqualTo(OLD);
                }).anySatisfy(pair -> {
                    assertThat(pair.key).isEqualTo("phase");
                    assertThat(pair.value).isEqualTo("after_commit");
                });
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getFormattedMessage()).doesNotContain("sensitive failure");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void imageRollback_cleanupFailure_preservesOriginalFailure(boolean group) {
        var fixture = fixture(group, false);
        fixture.failDelete.accept(NEW);
        var original = new IllegalStateException("original failure");
        assertThatThrownBy(() -> new TransactionTemplate(new TestTransactionManager()).execute(status -> {
            fixture.update.run();
            throw original;
        })).isSameAs(original);
        fixture.verifyDeleted.accept(NEW);
        fixture.verifyNotDeleted.accept(OLD);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void imageRollback_newImage_deleted(boolean group) {
        var fixture = fixture(group, false);
        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            fixture.update.run();
            status.setRollbackOnly();
        });
        fixture.verifyDeleted.accept(NEW);
        fixture.verifyNotDeleted.accept(OLD);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void imageCompletion_unknownOutcome_preservesBothImages(boolean group) {
        var fixture = fixture(group, false);
        TransactionSynchronizationManager.initSynchronization();
        try {
            fixture.update.run();
            TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_UNKNOWN);
            fixture.verifyNotDeleted.accept(NEW);
            fixture.verifyNotDeleted.accept(OLD);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void imageUpdate_unchangedImage_notDeleted(boolean group) {
        var fixture = fixture(group, true);
        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> fixture.update.run());
        fixture.verifyNotDeleted.accept(OLD);
        fixture.verifyNotDeleted.accept(NEW);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void directCallAlsoIsolatesCleanupFailure(boolean group) {
        var fixture = fixture(group, false);
        fixture.failDelete.accept(OLD);
        assertThatCode(fixture.update::run).doesNotThrowAnyException();
        fixture.verifyDeleted.accept(OLD);
        fixture.verifyNotDeleted.accept(NEW);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void groupCreationCleanupRunsOnlyOnRollback(boolean rollback) {
        var storage = mock(GroupImageStoragePort.class);
        var support = new GroupImageTransactionSupport(storage);
        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status -> {
            support.deleteOnRollback(NEW);
            if (rollback) {
                status.setRollbackOnly();
            }
        });
        verify(storage, times(rollback ? 1 : 0)).delete(NEW);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void resetOrMissingPreviousImageIsSafe(boolean reset) {
        var storage = mock(GroupImageStoragePort.class);
        var support = new GroupImageTransactionSupport(storage);
        new TransactionTemplate(new TestTransactionManager()).executeWithoutResult(status ->
                support.replaceAfterCompletion(reset ? OLD : null, reset ? null : NEW, reset ? null : NEW));
        verify(storage, times(reset ? 1 : 0)).delete(OLD);
        verify(storage, never()).delete(NEW);
    }

    private Fixture fixture(boolean group, boolean unchanged) {
        if (group) {
            var storage = mock(GroupImageStoragePort.class);
            var support = new GroupImageTransactionSupport(storage);
            return new Fixture(() -> support.replaceAfterCompletion(OLD, unchanged ? OLD : NEW, unchanged ? null : NEW),
                    file -> doThrow(new IllegalStateException("sensitive failure")).when(storage).delete(file),
                    file -> verify(storage).delete(file), file -> verify(storage, never()).delete(file));
        }
        var storage = mock(ProfileImageStoragePort.class);
        var actor = mock(CurrentActorPort.class);
        var load = mock(LoadUserPort.class);
        var save = mock(SaveUserPort.class);
        var user = mock(User.class);
        when(actor.getCurrentActor()).thenReturn(new CurrentActor(1L));
        when(load.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(user.active()).thenReturn(true);
        when(user.publicId()).thenReturn("current.id");
        when(user.avatarFileName()).thenReturn(OLD);
        when(user.changeProfile(any(), any(), any())).thenReturn(user);
        when(save.update(user)).thenReturn(user);
        when(storage.save(any())).thenReturn(NEW);
        var service = new UpdateMyProfileService(actor, load, save, storage, new PublicIdPolicy(), new ProfileImagePolicy());
        byte[] content = unchanged ? null : new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff};
        return new Fixture(() -> service.update(new UpdateMyProfileCommand(null, content, false)),
                file -> doThrow(new IllegalStateException("sensitive failure")).when(storage).delete(file),
                file -> verify(storage).delete(file), file -> verify(storage, never()).delete(file));
    }

    private record Fixture(Runnable update, Consumer<String> failDelete,
                           Consumer<String> verifyDeleted, Consumer<String> verifyNotDeleted) {}

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {
        private boolean committed;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) { committed = true; }
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }
}
