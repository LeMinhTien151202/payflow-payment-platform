package com.payflow.payment.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.payment.application.inbox.IncomingEventIdentity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcProcessedEventStoreTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final JdbcProcessedEventStore store = new JdbcProcessedEventStore(jdbc);
    private final IncomingEventIdentity event = new IncomingEventIdentity(
            UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
            "payment-risk-assessment-v1",
            "risk.assessment.completed",
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            Instant.parse("2026-07-28T00:00:00Z"));

    @Test
    void oneAffectedRowGrantsBusinessProcessing() {
        when(jdbc.update(eq(JdbcProcessedEventStore.INSERT_IF_NEW), any(MapSqlParameterSource.class)))
                .thenReturn(1);

        assertThat(store.recordIfNew(event)).isTrue();
        verify(jdbc).update(eq(JdbcProcessedEventStore.INSERT_IF_NEW), any(MapSqlParameterSource.class));
        assertThat(JdbcProcessedEventStore.INSERT_IF_NEW.toLowerCase())
                .contains("on conflict (event_id, consumer_name) do nothing");
    }

    @Test
    void zeroAffectedRowsClassifiesDuplicateWithoutException() {
        when(jdbc.update(eq(JdbcProcessedEventStore.INSERT_IF_NEW), any(MapSqlParameterSource.class)))
                .thenReturn(0);

        assertThat(store.recordIfNew(event)).isFalse();
    }
}

