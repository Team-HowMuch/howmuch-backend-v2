package com.amateur.howmuchbackend.adapter.out.storage;

import com.amateur.howmuchbackend.application.group.model.GroupImageUpload;
import com.amateur.howmuchbackend.infra.group.config.GroupImageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("로컬 그룹 이미지 저장")
class LocalGroupImageStorageAdapterTest {
    @TempDir
    Path storageDirectory;

    @Test
    @DisplayName("UUID 파일명으로 원자 저장하고 같은 content type으로 조회한다")
    void groupImage_uuidFilename_savedAndLoaded() {
        var adapter = new LocalGroupImageStorageAdapter(
                new GroupImageProperties(storageDirectory.toString())
        );

        String fileName = adapter.save(new GroupImageUpload(
                "image/png",
                "png",
                new byte[]{1, 2, 3}
        ));

        assertThat(fileName).matches("^[0-9a-f-]{36}\\.png$");
        assertThat(Files.isRegularFile(storageDirectory.resolve(fileName))).isTrue();
        var loaded = adapter.load(fileName).orElseThrow();
        assertThat(loaded.contentType()).isEqualTo("image/png");
        assertThat(loaded.content()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("경로 이동이 가능한 파일명은 조회하거나 삭제하지 않는다")
    void rejectsUnsafeFileName() {
        var adapter = new LocalGroupImageStorageAdapter(
                new GroupImageProperties(storageDirectory.toString())
        );

        assertThat(adapter.load("../outside.png")).isEmpty();
        adapter.delete("../outside.png");
        assertThat(storageDirectory).isEmptyDirectory();
    }
}
