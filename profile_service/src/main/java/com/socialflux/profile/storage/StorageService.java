package com.socialflux.profile.storage;

import java.util.Optional;

/** Armazenamento de objetos pequenos (avatares). Hoje disco local; trocável por S3/MinIO sem mexer no domínio. */
public interface StorageService {

    void put(String key, byte[] data);

    Optional<byte[]> get(String key);

    /** Silencioso se não existir. */
    void delete(String key);
}
