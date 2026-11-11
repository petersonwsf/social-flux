package com.socialflux.profile.legacy;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.ZoneOffset;

/** Traduz os eventos {@code profile.*} em escritas idempotentes no {@code usuario} do MySQL legado. */
@Component
@ConditionalOnProperty(prefix = "profile.legacy", name = "enabled", havingValue = "true")
public class LegacyProjectionHandler {

    private final LegacyUsuarioWriter writer;
    private final JsonMapper mapper;

    public LegacyProjectionHandler(LegacyUsuarioWriter writer, JsonMapper mapper) {
        this.writer = writer;
        this.mapper = mapper;
    }

    public void handle(String json) {
        JsonNode event = mapper.readTree(json);
        String type = event.path("type").asString();
        long version = event.path("aggregateVersion").asLong();
        JsonNode data = event.path("data");
        int legacyId = data.path("legacyId").asInt();

        switch (type) {
            case "profile.created.v1", "profile.updated.v1" -> writer.upsert(
                    legacyId,
                    data.path("username").asString(),
                    data.path("displayName").asString(),
                    nullable(data.path("bio")),
                    nullable(data.path("avatarKey")),
                    data.path("isPrivate").asBoolean(),
                    Instant.parse(data.path("createdAt").asString()).atZone(ZoneOffset.UTC).toLocalDate(),
                    version);
            case "profile.privacy-changed.v1" -> writer.setPrivate(legacyId, data.path("isPrivate").asBoolean(), version);
            case "profile.deleted.v1" -> writer.delete(legacyId);
            default -> { /* tipos novos são ignorados */ }
        }
    }

    private static String nullable(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asString();
    }
}
