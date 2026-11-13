package com.socialflux.graph.legacy;

import com.socialflux.graph.events.Amqp;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "socialflux.events", name = "enabled", havingValue = "true")
public class LegacyProjectionListener {

    private final Optional<LegacyProjectionHandler> handler;

    public LegacyProjectionListener(Optional<LegacyProjectionHandler> handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = Amqp.LEGACY_QUEUE, autoStartup = "${graph.legacy.enabled:false}")
    void onMessage(Message message) {
        handler.ifPresent(h -> h.handle(new String(message.getBody(), StandardCharsets.UTF_8)));
    }
}
