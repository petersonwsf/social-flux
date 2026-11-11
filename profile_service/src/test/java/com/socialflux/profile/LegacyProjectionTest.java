package com.socialflux.profile;

import com.socialflux.profile.legacy.LegacyProjectionHandler;
import com.socialflux.profile.support.AbstractProfileTest;
import com.socialflux.profile.support.TestSupportConfig.RecordingPublisher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A projeção na tabela {@code usuario} do legado. Usa H2 em modo MySQL só como "MySQL de teste" (SQL simples e padrão);
 * os eventos aplicados são os que o serviço realmente gera (relay → mensagem JSON → handler).
 */
@TestPropertySource(properties = {
        "profile.legacy.enabled=true",
        "profile.legacy.url=" + LegacyProjectionTest.URL,
        "profile.legacy.username=sa",
        "profile.legacy.password="
})
class LegacyProjectionTest extends AbstractProfileTest {

    static final String URL = "jdbc:h2:mem:legacy;MODE=MySQL;DB_CLOSE_DELAY=-1";

    @Autowired
    LegacyProjectionHandler projection;

    @BeforeAll
    static void legacySchema() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement s = c.createStatement()) {
            // Mesmo formato de infra/mysql/schema.sql
            s.execute("""
                    create table if not exists usuario (
                        id int primary key, username varchar(60) not null unique, nome varchar(120) not null,
                        biografia text null, foto_perfil varchar(255) null, privado boolean not null default false,
                        data_criacao date not null, profile_version bigint not null default 0)""");
        }
    }

    private record Row(String username, String nome, String bio, String foto, boolean privado, long version) {
    }

    private Row row(int id) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select * from usuario where id = " + id)) {
            return rs.next() ? new Row(rs.getString("username"), rs.getString("nome"), rs.getString("biografia"),
                    rs.getString("foto_perfil"), rs.getBoolean("privado"), rs.getLong("profile_version")) : null;
        }
    }

    private List<String> eventsOf(UUID id) {
        drainOutbox();
        return publisher.sent.stream().filter(s -> s.json().contains(id.toString())).map(RecordingPublisher.Sent::json).toList();
    }

    @Test
    void realEventsAreProjectedIdempotentlyAndInOrder() throws Exception {
        Account account = createActive(uniqueUsername());
        http().send("PATCH", "/api/usuarios/" + account.legacyId(),
                "{\"nome\":\"Nome Atualizado\",\"biografia\":\"minha bio\"}", account.token());
        http().send("PATCH", "/api/usuarios/" + account.legacyId(), "{\"privado\":true}", account.token());
        List<String> events = eventsOf(account.id());
        assertThat(events).hasSize(4);   // created, updated, updated, privacy-changed

        for (String event : events) {
            projection.handle(event);
        }
        Row projected = row(account.legacyId());
        assertThat(projected).isNotNull();
        assertThat(projected.username()).isEqualTo(account.username());
        assertThat(projected.nome()).isEqualTo("Nome Atualizado");
        assertThat(projected.bio()).isEqualTo("minha bio");
        assertThat(projected.privado()).isTrue();

        // Entrega duplicada e FORA DE ORDEM (evento antigo depois do novo) não desfaz o estado
        long versionBefore = projected.version();
        events.forEach(projection::handle);
        projection.handle(events.get(0));
        projection.handle(events.get(1));
        assertThat(row(account.legacyId())).isEqualTo(projected);
        assertThat(row(account.legacyId()).version()).isEqualTo(versionBefore);
    }

    @Test
    void photoChangesAndDeletionAreProjected() throws Exception {
        Account account = createActive(uniqueUsername());
        eventsOf(account.id()).forEach(projection::handle);
        assertThat(row(account.legacyId()).foto()).isNull();

        byte[] png = new byte[0];
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(40, 40, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        png = out.toByteArray();
        String key = (String) http().multipart("PATCH", "/api/img/usuarios/" + account.legacyId(), account.token(),
                "foto_perfil", "a.png", "image/png", png, java.util.Map.of()).json().get("foto_perfil");
        eventsOf(account.id()).forEach(projection::handle);
        assertThat(row(account.legacyId()).foto()).isEqualTo(key);

        publishAccountEvent("account.deleted.v1", account.id());
        eventsOf(account.id()).forEach(projection::handle);
        assertThat(row(account.legacyId())).isNull();
    }

    @Test
    void theLegacyTableNeverReceivesPersonalData() throws Exception {
        Account account = createActive(uniqueUsername());
        eventsOf(account.id()).forEach(projection::handle);
        try (Connection c = DriverManager.getConnection(URL, "sa", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select * from usuario where id = " + account.legacyId())) {
            int columns = rs.getMetaData().getColumnCount();
            List<String> names = new java.util.ArrayList<>();
            for (int i = 1; i <= columns; i++) {
                names.add(rs.getMetaData().getColumnName(i).toLowerCase());
            }
            assertThat(names).doesNotContain("email", "senha", "telefone", "sexo", "data_nascimento");
        }
    }
}
