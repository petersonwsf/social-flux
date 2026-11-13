package com.socialflux.graph;

import com.socialflux.graph.legacy.LegacyProjectionHandler;
import com.socialflux.graph.support.AbstractGraphTest;
import com.socialflux.graph.support.TestSupportConfig.RecordingPublisher.Sent;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A projeção na tabela {@code relacao} do legado. Usa H2 em modo MySQL só como "MySQL de teste"; os eventos aplicados
 * são os que o serviço realmente gera (relay → mensagem JSON → handler).
 */
@TestPropertySource(properties = {
        "graph.legacy.enabled=true",
        "graph.legacy.url=" + LegacyProjectionTest.URL,
        "graph.legacy.username=sa",
        "graph.legacy.password="
})
class LegacyProjectionTest extends AbstractGraphTest {

    static final String URL = "jdbc:h2:mem:legacygraph;MODE=MySQL;DB_CLOSE_DELAY=-1";

    @Autowired
    LegacyProjectionHandler projection;

    @BeforeAll
    static void legacySchema() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement s = c.createStatement()) {
            // Mesmo formato de infra/mysql/schema.sql (usuario enxuto e relacao com FKs em cascata)
            s.execute("create table if not exists usuario (id int primary key, username varchar(60) not null)");
            s.execute("""
                    create table if not exists relacao (
                        id int auto_increment primary key, id_seguidor int not null, id_seguido int not null,
                        aceito boolean not null default false, unique (id_seguidor, id_seguido),
                        foreign key (id_seguidor) references usuario (id) on delete cascade,
                        foreign key (id_seguido) references usuario (id) on delete cascade)""");
        }
    }

    /** O que o profile_service projeta: a linha de {@code usuario} que o JOIN do legado exige. */
    private static void legacyUser(TestUser user) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement s = c.createStatement()) {
            s.execute("merge into usuario (id, username) key (id) values (" + user.legacyId + ", '" + user.username + "')");
        }
    }

    private static Integer accepted(TestUser follower, TestUser followee) throws SQLException {
        try (Connection c = DriverManager.getConnection(URL, "sa", ""); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("select aceito from relacao where id_seguidor = " + follower.legacyId
                     + " and id_seguido = " + followee.legacyId)) {
            return rs.next() ? (rs.getBoolean(1) ? 1 : 0) : null;
        }
    }

    private List<String> eventsOf(TestUser user) {
        publishedTypes(user);
        return publisher.sent.stream().filter(s -> s.json().contains(user.id.toString())).map(Sent::json).toList();
    }

    @Test
    void followAndUnfollowAreProjectedAsAcceptedRelations() throws Exception {
        TestUser ana = newUser();
        TestUser bia = newUser();
        legacyUser(ana);
        legacyUser(bia);

        follow(ana, bia);
        eventsOf(ana).forEach(projection::handle);
        assertThat(accepted(ana, bia)).isEqualTo(1);   // o feed do legado passa a enxergar a relação

        unfollow(ana, bia);
        eventsOf(ana).forEach(projection::handle);
        assertThat(accepted(ana, bia)).isNull();
    }

    @Test
    void pendingRequestsAreNotProjectedButTheAcceptedRelationIs() throws Exception {
        TestUser ana = newUser();
        TestUser priv = newUser(true);
        legacyUser(ana);
        legacyUser(priv);

        follow(ana, priv);
        eventsOf(ana).forEach(projection::handle);
        assertThat(accepted(ana, priv)).isNull();   // solicitação pendente: o legado não deve ver

        accept(priv, ana);
        eventsOf(ana).forEach(projection::handle);
        assertThat(accepted(ana, priv)).isEqualTo(1);
    }

    @Test
    void duplicatedAndOutOfOrderEventsConvergeToTheCurrentState() throws Exception {
        TestUser ana = newUser();
        TestUser bia = newUser();
        legacyUser(ana);
        legacyUser(bia);

        follow(ana, bia);
        unfollow(ana, bia);
        follow(ana, bia);   // estado final: seguindo
        List<String> events = eventsOf(ana);
        assertThat(events).hasSize(3);

        // Ordem invertida e repetida: o último "removed" chega por último, mas o estado atual é "seguindo"
        for (int i = events.size() - 1; i >= 0; i--) {
            projection.handle(events.get(i));
        }
        events.forEach(projection::handle);
        events.forEach(projection::handle);

        assertThat(accepted(ana, bia)).isEqualTo(1);
    }

    @Test
    void otherEventTypesAreIgnored() throws Exception {
        TestUser ana = newUser();
        TestUser priv = newUser(true);
        legacyUser(ana);
        legacyUser(priv);

        follow(ana, priv);   // follow.requested.v1
        unfollow(ana, priv); // follow.request-cancelled.v1

        eventsOf(ana).forEach(projection::handle);

        assertThat(accepted(ana, priv)).isNull();
    }
}
