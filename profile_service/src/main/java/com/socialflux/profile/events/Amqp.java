package com.socialflux.profile.events;

import com.socialflux.events.EventQueues;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Filas de consumo deste serviço. O exchange {@code topic} compartilhado ({@code socialflux.events}) e a fila de
 * mensagens mortas são declarados pela biblioteca {@code socialflux-events}; aqui só as filas do profile e as ligações.
 * <p>Routing keys = tipo do evento (ex.: {@code profile.updated.v1}, {@code account.registered.v1}).
 */
@Configuration
@ConditionalOnProperty(prefix = "socialflux.events", name = "enabled", havingValue = "true")
public class Amqp {

    public static final String ACCOUNT_QUEUE = "profile-service.account-events";
    public static final String LEGACY_QUEUE = "profile-service.legacy-projection";

    /** Eventos da conta (cadastro/exclusão) publicados pelo authentication_service. */
    @Bean
    Queue accountQueue() {
        return EventQueues.durable(ACCOUNT_QUEUE);
    }

    @Bean
    Binding accountBinding(@Qualifier("accountQueue") Queue accountQueue,
                           @Qualifier(EventQueues.EXCHANGE_BEAN) TopicExchange exchange) {
        return EventQueues.bind(accountQueue, exchange, "account.#");
    }

    /** Projeção temporária no MySQL legado: só existe quando habilitada. */
    @Bean
    @ConditionalOnProperty(prefix = "profile.legacy", name = "enabled", havingValue = "true")
    Queue legacyQueue() {
        return EventQueues.durable(LEGACY_QUEUE);
    }

    @Bean
    @ConditionalOnProperty(prefix = "profile.legacy", name = "enabled", havingValue = "true")
    Binding legacyBinding(@Qualifier("legacyQueue") Queue legacyQueue,
                          @Qualifier(EventQueues.EXCHANGE_BEAN) TopicExchange exchange) {
        return EventQueues.bind(legacyQueue, exchange, "profile.#");
    }
}
