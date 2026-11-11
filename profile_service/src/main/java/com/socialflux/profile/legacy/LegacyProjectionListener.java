package com.socialflux.profile.legacy;

import com.socialflux.profile.events.Amqp;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@ConditionalOnProperty(prefix = "profile.events", name = "enabled", havingValue = "true")
public class LegacyProjectionListener {

    private final java.util.Optional<LegacyProjectionHandler> handler;

    public LegacyProjectionListener(java.util.Optional<LegacyProjectionHandler> handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = Amqp.LEGACY_QUEUE, autoStartup = "${profile.legacy.enabled:false}")
    void onMessage(Message message) {
        handler.ifPresent(h -> h.handle(new String(message.getBody(), StandardCharsets.UTF_8)));
    }
}
