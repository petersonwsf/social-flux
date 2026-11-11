package com.socialflux.profile.events;

import com.socialflux.profile.service.ProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * Reage aos eventos do authentication_service: {@code account.registered.v1} ativa o perfil; {@code account.deleted.v1}
 * apaga os dados pessoais. Idempotente (inbox) e tolerante a tipos desconhecidos (evolução de contrato).
 */
@Component
public class AccountEventHandler {

    private static final Logger log = LoggerFactory.getLogger(AccountEventHandler.class);

    public static final String REGISTERED = "account.registered.v1";
    public static final String DELETED = "account.deleted.v1";

    private final ProfileService profiles;
    private final Inbox inbox;
    private final JsonMapper mapper;

    public AccountEventHandler(ProfileService profiles, Inbox inbox, JsonMapper mapper) {
        this.profiles = profiles;
        this.inbox = inbox;
        this.mapper = mapper;
    }

    public void handle(String json) {
        JsonNode event = mapper.readTree(json);
        UUID messageId = UUID.fromString(event.path("id").asString());
        String type = event.path("type").asString();
        UUID accountId = UUID.fromString(event.path("aggregateId").asString());

        switch (type) {
            case REGISTERED -> inbox.once(messageId, () -> profiles.activate(accountId));
            case DELETED -> inbox.once(messageId, () -> profiles.deleteAccountData(accountId));
            default -> log.debug("Evento ignorado: {}", type);
        }
    }
}
