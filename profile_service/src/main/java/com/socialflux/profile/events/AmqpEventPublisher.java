package com.socialflux.profile.events;

import com.socialflux.profile.config.ProfileProperties;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Publica no RabbitMQ com mensagem persistente e CONFIRMAÇÃO do broker (publisher confirms). */
@Component
@ConditionalOnProperty(prefix = "profile.events", name = "enabled", havingValue = "true")
public class AmqpEventPublisher implements EventPublisher {

    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate template;
    private final String exchange;

    public AmqpEventPublisher(RabbitTemplate template, ProfileProperties properties) {
        this.template = template;
        this.exchange = properties.events().exchange();
    }

    @Override
    public void publish(UUID messageId, String routingKey, String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setMessageId(messageId.toString());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), properties);
        template.invoke(operations -> {
            operations.send(exchange, routingKey, message);
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return true;
        });
    }
}
