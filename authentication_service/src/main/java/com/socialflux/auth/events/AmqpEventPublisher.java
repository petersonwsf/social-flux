package com.socialflux.auth.events;

import com.socialflux.auth.config.AuthProperties;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publica no RabbitMQ com mensagem persistente e CONFIRMAÇÃO do broker. Com {@code mandatory} (padrão), um evento sem
 * nenhuma fila ligada é tratado como FALHA e continua pendente no outbox: os eventos de conta têm um consumidor
 * obrigatório (profile_service), então nunca devem ser descartados só porque ele ainda não subiu.
 */
@Component
@ConditionalOnProperty(prefix = "auth.events", name = "enabled", havingValue = "true")
public class AmqpEventPublisher implements EventPublisher {

    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate template;
    private final String exchange;
    private final boolean mandatory;
    private final Set<String> unroutable = ConcurrentHashMap.newKeySet();

    public AmqpEventPublisher(RabbitTemplate template, AuthProperties properties) {
        this.template = template;
        this.exchange = properties.events().exchange();
        this.mandatory = properties.events().mandatory();
        template.setMandatory(mandatory);
        template.setReturnsCallback(returned -> {
            String id = returned.getMessage().getMessageProperties().getMessageId();
            if (id != null) {
                unroutable.add(id);
            }
        });
    }

    @Override
    public void publish(UUID messageId, String routingKey, String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setMessageId(messageId.toString());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), properties);
        unroutable.remove(messageId.toString());
        template.invoke(operations -> {
            operations.send(exchange, routingKey, message);
            operations.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return true;
        });
        // O broker devolve (basic.return) ANTES de confirmar; se voltou, ninguém está escutando este evento ainda.
        if (unroutable.remove(messageId.toString())) {
            throw new IllegalStateException("Nenhuma fila ligada a " + routingKey + " (o consumidor ainda não subiu?)");
        }
    }

    /** Declara o exchange compartilhado. Cada consumidor declara a SUA fila (ver profile_service). */
    @Configuration
    @ConditionalOnProperty(prefix = "auth.events", name = "enabled", havingValue = "true")
    static class Topology {

        @Bean
        TopicExchange eventsExchange(AuthProperties properties) {
            return new TopicExchange(properties.events().exchange(), true, false);
        }
    }
}
