package com.socialflux.profile;

import com.socialflux.profile.config.ProfileProperties;
import com.socialflux.profile.storage.AvatarKeys;
import com.socialflux.profile.storage.AvatarProcessor;
import com.socialflux.profile.storage.LocalDiskStorage;
import com.socialflux.profile.web.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvatarProcessorTest {

    private static ProfileProperties properties(Path dir, long maxBytes, long maxPixels) {
        return new ProfileProperties(
                new ProfileProperties.Security("http://x/jwks", "iss", "aud", "svc", java.time.Duration.ofSeconds(60)),
                List.of(), new ProfileProperties.Storage(dir.toString(), maxBytes, maxPixels),
                new ProfileProperties.Legacy(false, null, null, null));
    }

    @Test
    void rejectsFilesOverTheByteLimitBeforeDecoding(@TempDir Path dir) {
        AvatarProcessor processor = new AvatarProcessor(properties(dir, 1024, 1_000_000));
        byte[] big = new byte[2048];
        big[0] = (byte) 0x89;
        assertThatThrownBy(() -> processor.process(big))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("file_too_large"));
        assertThatThrownBy(() -> processor.process(new byte[0]))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("missing_file"));
    }

    @Test
    void storageNeverResolvesOutsideItsDirectory(@TempDir Path dir) throws Exception {
        LocalDiskStorage storage = new LocalDiskStorage(properties(dir, 1024, 1000));
        Path outside = Files.createTempFile("outside", ".jpg");

        for (String malicious : List.of("../" + outside.getFileName(), "..\\x.jpg", "/etc/passwd", "av_../x.jpg",
                outside.toString(), "av_" + UUID.randomUUID() + ".jpg/../../x")) {
            assertThat(storage.get(malicious)).as(malicious).isEmpty();
            storage.delete(malicious);                                    // silencioso, sem efeito
            assertThatThrownBy(() -> storage.put(malicious, new byte[]{1}))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Files.exists(outside)).isTrue();

        String key = AvatarKeys.newKey(Clock.systemUTC());
        storage.put(key, new byte[]{1, 2, 3});
        assertThat(storage.get(key)).contains(new byte[]{1, 2, 3});
        storage.delete(key);
        assertThat(storage.get(key)).isEmpty();
        Files.deleteIfExists(outside);
    }

    @Test
    void keysFollowTheServerGeneratedFormat() {
        String key = AvatarKeys.newKey(Clock.systemUTC());
        assertThat(AvatarKeys.isValid(key)).isTrue();
        assertThat(AvatarKeys.isValid(null)).isFalse();
        assertThat(AvatarKeys.isValid("av_x.jpg")).isFalse();
        assertThat(AvatarKeys.isValid(key + "/..")).isFalse();
    }
}
