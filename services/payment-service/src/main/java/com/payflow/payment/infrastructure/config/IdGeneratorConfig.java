package com.payflow.payment.infrastructure.config;

import com.payflow.payment.application.port.IdGenerator;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the identifier generator for aggregates whose id is assigned by the application.
 *
 * <p>Ids are chosen here rather than by the database because a payment's id appears in the response, in its
 * status history and in its outbox event, all inside the transaction that creates it. Waiting for a generated
 * key would mean writing the payment first and only then knowing what to reference.
 */
@Configuration
class IdGeneratorConfig {

    /**
     * Random (v4) rather than time-ordered (v7). A v7 id embeds its creation time, and a payment id is handed
     * to merchants and appears in URLs; ordering it would leak volume — two ids taken minutes apart reveal how
     * many payments the platform accepted in between. Index locality is not worth that.
     */
    @Bean
    IdGenerator idGenerator() {
        return UUID::randomUUID;
    }
}
