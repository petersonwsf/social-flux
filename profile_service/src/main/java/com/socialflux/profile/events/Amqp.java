package com.socialflux.profile.events;

import com.socialflux.profile.config.ProfileProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topologia do RabbitMQ deste serviço. Exchange {@code topic} durável compartilhado ({@code socialflux.events}); cada
 * consumidor tem a SUA fila durável (com fila de mensagens mortas) e a declara ao subir — por isso um serviço novo
 * só precisa declarar a própria fila e a ligação com o padrão de eventos que lhe interessa.
 * <p>Routing keys = tipo do evento (ex.: {@code profile.updated.v1}, {@code account.registered.v1}).
 */
@Configuration
@ConditionalOnProperty(prefix = "profile.events", name = "enabled", havingValue = "true")
public class Amqp {

    public static final String ACCOUNT_QUEUE = "profile-service.account-events";
    public static final String LEGACY_QUEUE = "profile-service.legacy-projection";
    public static final String DEAD_EXCHANGE = "socialflux.events.dlx";
    public static final String DEAD_QUEUE = "socialflux.events.dead";

    @Bean
    TopicExchange eventsExchange(ProfileProperties properties) {
        return new TopicExchange(properties.events().exchange(), true, false);
    }

    @Bean
    TopicExchange deadLetterExchange() {
        return new TopicExchange(DEAD_EXCHANGE, true, false);
    }

    @Bean
    Queue deadQueue() {
        return QueueBuilder.durable(DEAD_QUEUE).build();
    }

    @Bean
    Binding deadBinding(Queue deadQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(deadQueue).to(deadLetterExchange).with("#");
    }

    /** Eventos da conta (cadastro/exclusão) publicados pelo authentication_service. */
    @Bean
    Queue accountQueue() {
        return QueueBuilder.durable(ACCOUNT_QUEUE).deadLetterExchange(DEAD_EXCHANGE).build();
    }

    @Bean
    Binding accountBinding(Queue accountQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(accountQueue).to(eventsExchange).with("account.#");
    }

    /** Projeção temporária no MySQL legado: só existe quando habilitada. */
    @Bean
    @ConditionalOnProperty(prefix = "profile.legacy", name = "enabled", havingValue = "true")
    Queue legacyQueue() {
        return QueueBuilder.durable(LEGACY_QUEUE).deadLetterExchange(DEAD_EXCHANGE).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "profile.legacy", name = "enabled", havingValue = "true")
    Binding legacyBinding(Queue legacyQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(legacyQueue).to(eventsExchange).with("profile.#");
    }
}
