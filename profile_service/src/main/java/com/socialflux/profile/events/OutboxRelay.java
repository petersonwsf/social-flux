package com.socialflux.profile.events;

import com.socialflux.profile.config.ProfileProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/** Publica os eventos pendentes do outbox (pelo menos uma vez, em ordem). Falha de broker não perde nada: tenta de novo. */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH = 100;

    private final Outbox outbox;
    private final ObjectProvider<EventPublisher> publisher;
    private final ProfileProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OutboxRelay(Outbox outbox, ObjectProvider<EventPublisher> publisher, ProfileProperties properties,
                       Clock clock, PlatformTransactionManager txManager) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${profile.events.relay-interval:PT0.5S}")
    void scheduled() {
        if (!properties.events().enabled()) {
            return;
        }
        try {
            publishPending();
        } catch (RuntimeException e) {
            log.warn("Falha ao publicar eventos do outbox (tentará de novo): {}", e.toString());
        }
    }

    /** Publica um lote e devolve quantos eventos foram enviados. */
    public int publishPending() {
        EventPublisher target = publisher.getIfAvailable();
        if (target == null) {
            return 0;
        }
        Integer sent = tx.execute(status -> {
            int count = 0;
            for (Outbox.Row row : outbox.lockPending(BATCH)) {
                try {
                    target.publish(row.id(), row.type(), row.payload());
                } catch (RuntimeException e) {
                    log.warn("Broker indisponível ao publicar {}: {}", row.type(), e.toString());
                    break; // preserva a ordem: não pula o evento que falhou
                }
                outbox.markPublished(row.id());
                count++;
            }
            return count;
        });
        return sent == null ? 0 : sent;
    }

    /** Remove eventos já publicados há mais de 7 dias. */
    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT1M")
    void purge() {
        try {
            int removed = outbox.deletePublishedBefore(clock.instant().minus(Duration.ofDays(7)));
            if (removed > 0) {
                log.info("Outbox: {} eventos antigos removidos", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Falha ao limpar o outbox: {}", e.toString());
        }
    }
}
