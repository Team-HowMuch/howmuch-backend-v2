package com.amateur.howmuchbackend.adapter.out.storage;

import com.amateur.howmuchbackend.application.group.model.GroupImage;
import com.amateur.howmuchbackend.application.group.model.GroupImageUpload;
import com.amateur.howmuchbackend.application.port.out.group.GroupImageStoragePort;
import com.amateur.howmuchbackend.infra.group.config.GroupImageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class LocalGroupImageStorageAdapter implements GroupImageStoragePort {
    private static final Pattern FILE_NAME_PATTERN = Pattern.compile("^[0-9a-f-]{36}\\.(jpg|png|webp)$");
    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "jpg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp"
    );

    private final GroupImageProperties properties;

    @Override
    public String save(GroupImageUpload image) {
        String extension = image.extension();
        String expectedContentType = CONTENT_TYPES.get(extension);
        if (!image.contentType().equals(expectedContentType)) {
            throw new IllegalArgumentException("Unsupported group image metadata.");
        }

        String fileName = UUID.randomUUID() + "." + extension;
        Path directory = storageDirectory();
        Path temporaryFile = directory.resolve(fileName + ".tmp");
        Path targetFile = directory.resolve(fileName);
        try {
            Files.createDirectories(directory);
            Files.write(temporaryFile, image.content());
            move(temporaryFile, targetFile);
            return fileName;
        } catch (IOException exception) {
            deleteQuietly(temporaryFile);
            deleteQuietly(targetFile);
            throw new IllegalStateException("Unable to store group image.", exception);
        }
    }

    @Override
    public Optional<GroupImage> load(String fileName) {
        if (!isValidFileName(fileName)) {
            return Optional.empty();
        }
        Path file = storageDirectory().resolve(fileName);
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            return Optional.of(new GroupImage(contentTypeOf(fileName), Files.readAllBytes(file)));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to load group image.", exception);
        }
    }

    @Override
    public void delete(String fileName) {
        if (!isValidFileName(fileName)) {
            return;
        }
        deleteQuietly(storageDirectory().resolve(fileName));
    }

    private Path storageDirectory() {
        return Path.of(properties.storageDir()).toAbsolutePath().normalize();
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean isValidFileName(String fileName) {
        return fileName != null && FILE_NAME_PATTERN.matcher(fileName).matches();
    }

    private String contentTypeOf(String fileName) {
        return CONTENT_TYPES.get(fileName.substring(fileName.lastIndexOf('.') + 1));
    }

    private void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to delete group image.", exception);
        }
    }
}
