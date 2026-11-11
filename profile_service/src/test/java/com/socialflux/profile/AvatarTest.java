package com.socialflux.profile;

import com.socialflux.profile.support.AbstractProfileTest;
import com.socialflux.profile.support.Http.Response;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AvatarTest extends AbstractProfileTest {

    private static final String KEY_FORMAT = "av_[0-9a-f-]{36}\\.jpg";

    private static byte[] png(int width, int height, boolean alpha) throws IOException {
        BufferedImage image = new BufferedImage(width, height, alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(200, 30, 30, alpha ? 128 : 255));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLUE);
        g.fillOval(width / 4, height / 4, width / 2, height / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private Response upload(Account account, String pathId, byte[] content, String filename, Map<String, String> fields) {
        return http().multipart("PATCH", "/api/img/usuarios/" + pathId, account.token(), "foto_perfil", filename,
                "image/png", content, fields);
    }

    @Test
    void uploadsAreReEncodedToASquareJpegServedWithSafeHeaders() throws Exception {
        Account account = createActive(uniqueUsername());

        Response response = upload(account, String.valueOf(account.legacyId()), png(300, 200, true), "minha.png", Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        String key = (String) response.json().get("foto_perfil");
        assertThat(key).matches(KEY_FORMAT);
        assertThat(response.json().get("foto_url")).isEqualTo("/media/avatars/" + key);
        assertThat(Files.exists(STORAGE.resolve(key))).isTrue();

        // Entrega pública, com tipo fixo, nosniff e cache imutável (o nome muda a cada troca de foto)
        Response media = http().get("/media/avatars/" + key, null);
        assertThat(media.status()).isEqualTo(200);
        assertThat(media.header("Content-Type")).isEqualTo("image/jpeg");
        assertThat(media.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(media.header("Cache-Control")).contains("immutable").contains("public");
        BufferedImage served = ImageIO.read(new ByteArrayInputStream(media.bytes()));
        assertThat(served.getWidth()).isEqualTo(256);
        assertThat(served.getHeight()).isEqualTo(256);
        assertThat(media.bytes()[0] & 0xFF).isEqualTo(0xFF);   // magic bytes de JPEG: o PNG original não é guardado

        // Outro usuário vê a foto (perfil público)
        Account viewer = createActive(uniqueUsername());
        assertThat(http().get("/api/usuarios?username=" + account.username(), viewer.token()).json().get("foto_perfil"))
                .isEqualTo(key);
    }

    @Test
    void replacingThePhotoDeletesTheOldFileAndIgnoresTheClientSuppliedOldName() throws Exception {
        Account account = createActive(uniqueUsername());
        Path victim = Files.createTempFile(STORAGE.getParent(), "victim", ".txt");   // arquivo FORA do diretório de avatares

        String first = (String) upload(account, String.valueOf(account.legacyId()), png(64, 64, false), "a.png", Map.of())
                .json().get("foto_perfil");
        // O legado apagava qualquer arquivo indicado em `foto_antiga` (path traversal). Aqui o campo é ignorado.
        Response second = upload(account, String.valueOf(account.legacyId()), png(80, 80, false), "b.png",
                Map.of("foto_antiga", "../" + victim.getFileName(), "foto_antiga2", first));
        assertThat(second.status()).isEqualTo(200);
        String secondKey = (String) second.json().get("foto_perfil");

        assertThat(secondKey).isNotEqualTo(first);
        assertThat(Files.exists(victim)).as("arquivo fora do diretório não pode ser tocado").isTrue();
        assertThat(Files.exists(STORAGE.resolve(first))).as("a foto anterior é removida").isFalse();
        assertThat(Files.exists(STORAGE.resolve(secondKey))).isTrue();
        assertThat(http().get("/media/avatars/" + first, null).status()).isEqualTo(404);
        Files.deleteIfExists(victim);
    }

    @Test
    void onlyTheOwnerCanChangeThePhoto() throws Exception {
        Account a = createActive(uniqueUsername());
        Account b = createActive(uniqueUsername());

        Response attack = upload(a, String.valueOf(b.legacyId()), png(64, 64, false), "x.png", Map.of());
        assertThat(attack.status()).isEqualTo(403);
        assertThat(http().get("/api/usuarios/me", b.token()).json().get("foto_perfil")).isNull();
        assertThat(http().multipart("PATCH", "/api/img/usuarios/" + a.legacyId(), null, "foto_perfil", "x.png",
                "image/png", png(64, 64, false), Map.of()).status()).isEqualTo(401);
    }

    @Test
    void rejectsFakeCorruptAndOversizedImages() throws Exception {
        Account account = createActive(uniqueUsername());
        String id = String.valueOf(account.legacyId());

        // Texto com extensão/Content-Type de imagem (o legado só olhava a extensão)
        Response fake = upload(account, id, text("<html><script>alert(1)</script></html>"), "x.png", Map.of());
        assertThat(fake.status()).isEqualTo(400);
        assertThat(fake.json().get("codigo")).isEqualTo("invalid_image");

        // Bytes mágicos de JPEG com conteúdo corrompido
        byte[] corrupt = new byte[2048];
        corrupt[0] = (byte) 0xFF;
        corrupt[1] = (byte) 0xD8;
        corrupt[2] = (byte) 0xFF;
        assertThat(upload(account, id, corrupt, "x.jpg", Map.of()).json().get("codigo")).isEqualTo("invalid_image");

        // GIF (formato não permitido)
        assertThat(upload(account, id, text("GIF89a......"), "x.gif", Map.of()).status()).isEqualTo(400);

        // "Image bomb": arquivo minúsculo que expande para 25 megapixels (limite: 16 MP)
        Response bomb = upload(account, id, png(5000, 5000, false), "bomb.png", Map.of());
        assertThat(bomb.status()).isEqualTo(400);
        assertThat(bomb.json().get("codigo")).isEqualTo("image_too_large");

        // Parte ausente
        Response missing = http().multipart("PATCH", "/api/img/usuarios/" + id, account.token(), "foto_perfil", "x.png",
                "image/png", null, Map.of());
        assertThat(missing.status()).isEqualTo(400);

        assertThat(http().get("/api/usuarios/me", account.token()).json().get("foto_perfil")).isNull();
    }

    @Test
    void mediaEndpointOnlyServesServerGeneratedKeys() {
        assertThat(http().get("/media/avatars/av_x.jpg", null).status()).isEqualTo(404);
        assertThat(http().get("/media/avatars/" + "av_" + UUID.randomUUID() + ".jpg", null).status()).isEqualTo(404);
        assertThat(http().get("/media/avatars/..%2F..%2Fetc%2Fpasswd", null).status()).isIn(400, 404);
        assertThat(http().get("/media/avatars/passwd", null).status()).isEqualTo(404);
    }
}
