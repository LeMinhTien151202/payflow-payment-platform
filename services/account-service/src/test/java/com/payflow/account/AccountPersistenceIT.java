package com.payflow.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.account.application.handler.HandleReserveFundsRequestedHandler;
import com.payflow.events.EventEnvelope;
import com.payflow.events.account.AccountEvents;
import com.payflow.events.account.AccountReserveRequestedData;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Proves Account uses a private database and serializes competing reservations. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AccountPersistenceIT extends AbstractPostgresIT {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private HandleReserveFundsRequestedHandler reserve;

    @Test
    void concurrentReservationsCannotOverdrawAvailableBalance() throws Exception {
        UUID accountId = UUID.randomUUID();
        jdbc.update(
                "insert into account.accounts"
                        + " (id,currency,available_balance,reserved_balance,status)"
                        + " values (?,'VND',100,0,'ACTIVE')",
                accountId);

        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                handleAfterBarrier(command(accountId), ready, start);
                return null;
            });
            var second = executor.submit(() -> {
                handleAfterBarrier(command(accountId), ready, start);
                return null;
            });
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }

        assertThat(jdbc.queryForObject(
                        "select available_balance from account.accounts where id=?",
                        BigDecimal.class,
                        accountId))
                .isEqualByComparingTo("20.0000");
        assertThat(jdbc.queryForObject(
                        "select reserved_balance from account.accounts where id=?",
                        BigDecimal.class,
                        accountId))
                .isEqualByComparingTo("80.0000");
        assertThat(jdbc.queryForObject(
                        "select count(*) from account.balance_reservations where account_id=?",
                        Integer.class,
                        accountId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from account_runtime.outbox_events"
                                + " where event_type='account.funds-reservation-failed'",
                        Integer.class))
                .isEqualTo(1);
    }

    private void handleAfterBarrier(
            EventEnvelope<AccountReserveRequestedData> event,
            CountDownLatch ready,
            CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        start.await();
        reserve.handle(event);
    }

    private static EventEnvelope<AccountReserveRequestedData> command(UUID accountId) {
        UUID paymentId = UUID.randomUUID();
        Instant now = Instant.now();
        return EventEnvelope.of(
                UUID.randomUUID(),
                AccountEvents.RESERVE_REQUESTED,
                paymentId.toString(),
                "corr-account-concurrency-it",
                "payment-service",
                now,
                new AccountReserveRequestedData(
                        paymentId,
                        accountId,
                        new BigDecimal("80.0000"),
                        "VND",
                        now.plusSeconds(300)));
    }
}
