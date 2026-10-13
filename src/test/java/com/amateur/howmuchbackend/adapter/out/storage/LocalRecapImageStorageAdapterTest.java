package com.amateur.howmuchbackend.adapter.out.storage;

import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.recap.RecapImageCleanupStorePort;
import com.amateur.howmuchbackend.application.recap.service.RecapImageCleanupService;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import com.amateur.howmuchbackend.infra.recap.RecapImageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class LocalRecapImageStorageAdapterTest {
    @TempDir Path directory;
    private final ProfileImageStoragePort profiles = mock(ProfileImageStoragePort.class);
    private LocalRecapImageStorageAdapter adapter() {
        return new LocalRecapImageStorageAdapter(new RecapImageProperties(directory.toString()), profiles);
    }

    @Test
    void copiesAtomicallyAndDeletesBothReservedAndTemporaryFile() throws Exception {
        String target = UUID.randomUUID() + ".png";
        when(profiles.load("original.png")).thenReturn(Optional.of(new ProfileImage("image/png", new byte[]{1,2})));
        adapter().verifyReady();
        adapter().copyProfile("original.png", target);
        assertThat(adapter().load(target).orElseThrow().content()).containsExactly(1,2);
        assertThat(Files.exists(directory.resolve(target + ".tmp"))).isFalse();
        Files.write(directory.resolve(target + ".tmp"), new byte[]{3});
        adapter().delete(target); adapter().delete(target);
        assertThat(Files.list(directory).toList()).isEmpty();
    }

    @Test
    void listsOnlyOldReservedNamesIncludingTemporaryFiles() throws Exception {
        String old = UUID.randomUUID() + ".png", recent = UUID.randomUUID() + ".jpg";
        Files.write(directory.resolve(old + ".tmp"), new byte[]{1});
        Files.setLastModifiedTime(directory.resolve(old + ".tmp"), FileTime.from(LocalDateTime.now(ZoneOffset.UTC).minusDays(2).toInstant(ZoneOffset.UTC)));
        Files.write(directory.resolve(recent), new byte[]{1});
        Files.write(directory.resolve("unrelated.txt"), new byte[]{1});
        assertThat(adapter().findOlderThan(LocalDateTime.now(ZoneOffset.UTC).minusHours(24))).containsExactly(old);
        assertThatThrownBy(() -> adapter().delete("../secret.png")).isInstanceOf(IllegalArgumentException.class);
        assertThat(adapter().load("../secret.png")).isEmpty();
    }

    @Test
    void missingOrUnreadableImageAndInvalidStorageFailBeforePublication() throws Exception {
        String target = UUID.randomUUID() + ".png";
        when(profiles.load("missing.png")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> adapter().copyProfile("missing.png", target)).isInstanceOf(IllegalStateException.class);
        Path blockingFile = directory.resolve("file"); Files.write(blockingFile, new byte[]{1});
        var unavailable = new LocalRecapImageStorageAdapter(new RecapImageProperties(blockingFile.toString()), profiles);
        assertThatThrownBy(unavailable::verifyReady).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter().copyProfile("missing.png", "wrong.png")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundedScansContinuePastTheFirstThousandFilesAndRestartAfterTheEnd() throws Exception {
        List<String> names = oldImages(1101);
        var cutoff = LocalDateTime.now(ZoneOffset.UTC).minusHours(24);
        try (var storage = adapter()) {
            var first = storage.findOlderThan(cutoff);
            var next = storage.findOlderThan(cutoff);
            assertThat(first).hasSize(1000);
            assertThat(next).hasSize(101).doesNotContainAnyElementsOf(first);
            var visited = new HashSet<>(first); visited.addAll(next);
            assertThat(visited).containsExactlyInAnyOrderElementsOf(names);
            assertThat(storage.findOlderThan(cutoff)).hasSize(1000);
        }
    }

    @Test
    void referencedFilesDoNotPreventLaterOrphansFromBeingQueued() throws Exception {
        List<String> names = oldImages(1101);
        String orphan = names.getLast();
        var cleanup = mock(RecapImageCleanupStorePort.class);
        when(cleanup.isReferenced(anyString(), any())).thenAnswer(call -> !orphan.equals(call.getArgument(0)));
        when(cleanup.claimNext(any())).thenReturn(Optional.empty());
        try (var storage = adapter()) {
            var cleaner = new RecapImageCleanupService(cleanup, storage);
            cleaner.cleanBatch(100); cleaner.cleanBatch(100);
            verify(cleanup, times(1101)).isReferenced(anyString(), any());
            verify(cleanup).enqueue(eq(orphan), any());
            verify(cleanup, times(1)).enqueue(anyString(), any());
            assertThat(Files.exists(directory.resolve(names.getFirst()))).isTrue();
        }
    }

    private List<String> oldImages(int count) throws Exception {
        var names = new ArrayList<String>();
        var modified = FileTime.from(LocalDateTime.now(ZoneOffset.UTC).minusDays(2).toInstant(ZoneOffset.UTC));
        for (int index = 0; index < count; index++) {
            String name = UUID.randomUUID() + ".png"; names.add(name);
            Files.write(directory.resolve(name), new byte[]{1});
            Files.setLastModifiedTime(directory.resolve(name), modified);
        }
        return names;
    }
}
