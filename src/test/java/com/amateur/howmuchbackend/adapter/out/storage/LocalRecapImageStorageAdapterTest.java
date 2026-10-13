package com.amateur.howmuchbackend.adapter.out.storage;

import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
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
}
