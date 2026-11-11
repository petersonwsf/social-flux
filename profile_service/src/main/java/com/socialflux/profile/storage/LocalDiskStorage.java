package com.socialflux.profile.storage;

import com.socialflux.profile.config.ProfileProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/**
 * Disco local. Toda chave é validada contra {@link AvatarKeys} e o caminho final é normalizado e conferido contra
 * o diretório-base — nenhuma entrada do cliente chega a montar um caminho (sem path traversal).
 */
@Component
public class LocalDiskStorage implements StorageService {

    private final Path base;

    public LocalDiskStorage(ProfileProperties properties) {
        this.base = Path.of(properties.storage().dir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(base);
        } catch (IOException e) {
            throw new UncheckedIOException("Não foi possível criar o diretório de avatares " + base, e);
        }
    }

    @Override
    public void put(String key, byte[] data) {
        Path target = resolve(key);
        Path temp = base.resolve(".tmp-" + UUID.randomUUID());
        try {
            Files.write(temp, data);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // melhor esforço
            }
            throw new UncheckedIOException("Falha ao gravar o avatar", e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        if (!AvatarKeys.isValid(key)) {
            return Optional.empty();
        }
        Path file = resolve(key);
        try {
            return Files.isRegularFile(file) ? Optional.of(Files.readAllBytes(file)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o avatar", e);
        }
    }

    @Override
    public void delete(String key) {
        if (!AvatarKeys.isValid(key)) {
            return;
        }
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao remover o avatar", e);
        }
    }

    private Path resolve(String key) {
        if (!AvatarKeys.isValid(key)) {
            throw new IllegalArgumentException("Chave de avatar inválida");
        }
        Path target = base.resolve(key).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("Caminho fora do diretório de avatares");
        }
        return target;
    }
}
