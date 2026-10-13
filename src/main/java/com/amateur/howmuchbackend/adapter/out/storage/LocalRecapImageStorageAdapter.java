package com.amateur.howmuchbackend.adapter.out.storage;

import com.amateur.howmuchbackend.application.port.out.recap.RecapImageStoragePort;
import com.amateur.howmuchbackend.application.port.out.user.ProfileImageStoragePort;
import com.amateur.howmuchbackend.application.user.model.ProfileImage;
import com.amateur.howmuchbackend.infra.recap.RecapImageProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class LocalRecapImageStorageAdapter implements RecapImageStoragePort {
    private static final Pattern NAME = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|jpeg|png|webp)$");
    private static final Map<String, String> TYPES = Map.of("jpg", "image/jpeg", "jpeg", "image/jpeg", "png", "image/png", "webp", "image/webp");
    private final RecapImageProperties properties;
    private final ProfileImageStoragePort profiles;

    @Override
    public void verifyReady() {
        String probe = UUID.randomUUID() + ".png";
        try {
            Files.createDirectories(directory());
            Files.write(temporary(probe), new byte[]{0});
            Files.move(temporary(probe), file(probe), StandardCopyOption.ATOMIC_MOVE);
            Files.delete(file(probe));
        } catch (IOException exception) {
            throw new IllegalStateException("Recap storage must support durable writes and atomic moves", exception);
        } finally {
            delete(probe);
        }
    }

    @Override
    public void copyProfile(String sourceFileName, String reservedFileName) {
        requireName(reservedFileName);
        var image = profiles.load(sourceFileName)
                .orElseThrow(() -> new IllegalStateException("Selected companion profile image is missing"));
        if (!type(reservedFileName).equals(image.contentType())) {
            throw new IllegalStateException("Profile image content type mismatch");
        }
        try {
            Files.createDirectories(directory());
            Files.write(temporary(reservedFileName), image.content());
            Files.move(temporary(reservedFileName), file(reservedFileName), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            // Durable reservation cleanup removes both files, including after a crash.
            throw new IllegalStateException("Unable to copy recap image", exception);
        }
    }

    @Override
    public Optional<ProfileImage> load(String fileName) {
        if (fileName == null || !NAME.matcher(fileName).matches()) return Optional.empty();
        try {
            if (!Files.isRegularFile(file(fileName), LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            return Optional.of(new ProfileImage(type(fileName), Files.readAllBytes(file(fileName))));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read recap image", exception);
        }
    }

    @Override
    public void delete(String fileName) {
        requireName(fileName);
        try {
            Files.deleteIfExists(temporary(fileName));
            Files.deleteIfExists(file(fileName));
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to delete recap image", exception);
        }
    }

    @Override
    public List<String> findOlderThan(LocalDateTime cutoff) {
        if (!Files.isDirectory(directory())) return List.of();
        try (var entries = Files.list(directory())) {
            return entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> old(path, cutoff)).map(path -> path.getFileName().toString())
                    .map(name -> name.endsWith(".tmp") ? name.substring(0, name.length() - 4) : name)
                    .filter(name -> NAME.matcher(name).matches()).distinct().limit(1000).toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to list recap images", exception);
        }
    }

    private boolean old(Path path, LocalDateTime cutoff) {
        try { return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff.toInstant(ZoneOffset.UTC)); }
        catch (IOException exception) { throw new IllegalStateException("Unable to inspect recap image", exception); }
    }
    private Path directory() { return Path.of(properties.storageDir()).toAbsolutePath().normalize(); }
    private Path file(String name) { return directory().resolve(name); }
    private Path temporary(String name) { return directory().resolve(name + ".tmp"); }
    private String type(String name) { return TYPES.get(name.substring(name.lastIndexOf('.') + 1)); }
    private void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("Invalid recap image name");
    }
}
