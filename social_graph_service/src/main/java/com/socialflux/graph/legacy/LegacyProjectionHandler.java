package com.socialflux.graph.legacy;

import com.socialflux.events.EventEnvelope;
import com.socialflux.graph.domain.FollowRepository;
import com.socialflux.graph.domain.SnapshotRepository;
import com.socialflux.graph.domain.UserSnapshot;
import com.socialflux.graph.service.FollowWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

/**
 * Traduz os eventos {@code follow.created.v1} / {@code follow.removed.v1} em escritas na {@code relacao} do MySQL legado.
 * <p><b>Projeção por ESTADO, não por evento:</b> o evento só diz QUAL par mudou; o que vai para o MySQL é o estado ATUAL
 * do par no PostgreSQL (existe a aresta? liga; não existe? desliga). Assim, eventos duplicados ou fora de ordem — por
 * exemplo "deixou de seguir" chegando depois de "seguiu de novo" — convergem sempre para o estado correto, sem versões.
 * Solicitações pendentes não são projetadas (o legado só lê relações aceitas).
 */
@Component
@ConditionalOnProperty(prefix = "graph.legacy", name = "enabled", havingValue = "true")
public class LegacyProjectionHandler {

    private final LegacyRelacaoWriter writer;
    private final FollowRepository follows;
    private final SnapshotRepository snapshots;
    private final JsonMapper mapper;

    public LegacyProjectionHandler(LegacyRelacaoWriter writer, FollowRepository follows, SnapshotRepository snapshots,
                                   JsonMapper mapper) {
        this.writer = writer;
        this.follows = follows;
        this.snapshots = snapshots;
        this.mapper = mapper;
    }

    public void handle(String json) {
        EventEnvelope event = EventEnvelope.parse(mapper, json);
        if (!FollowWriter.FOLLOW_CREATED.equals(event.type()) && !FollowWriter.FOLLOW_REMOVED.equals(event.type())) {
            return;   // solicitações e tipos desconhecidos não interessam ao legado
        }
        UUID follower = uuid(event.data(), "followerId");
        UUID followee = uuid(event.data(), "followeeId");
        Optional<Integer> followerLegacy = legacyId(follower);
        Optional<Integer> followeeLegacy = legacyId(followee);
        if (followerLegacy.isEmpty() || followeeLegacy.isEmpty()) {
            return;   // conta excluída (túmulo sem id legado): o ON DELETE CASCADE do MySQL já limpou a relação
        }
        if (follows.isFollowing(follower, followee)) {
            writer.link(followerLegacy.get(), followeeLegacy.get());
        } else {
            writer.unlink(followerLegacy.get(), followeeLegacy.get());
        }
    }

    private Optional<Integer> legacyId(UUID userId) {
        return snapshots.find(userId).map(UserSnapshot::legacyId);
    }

    private static UUID uuid(JsonNode data, String field) {
        return UUID.fromString(data.path(field).asString());
    }
}
