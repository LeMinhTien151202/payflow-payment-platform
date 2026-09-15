package com.payflow.payment;

import static com.payflow.payment.application.CreatePaymentCommands.request;
import static com.payflow.payment.application.CreatePaymentCommands.MERCHANT_ID;
import static org.assertj.core.api.Assertions.assertThat;

import com.payflow.payment.application.CancelPaymentResult;
import com.payflow.payment.application.command.CancelPaymentCommand;
import com.payflow.payment.application.handler.CancelPaymentHandler;
import com.payflow.payment.application.handler.CreatePaymentHandler;
import com.payflow.payment.application.port.ManualReviewQueryPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** PostgreSQL evidence for cancellation persistence and the operations queue query. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PaymentCancellationAndOperationsPersistenceIT extends AbstractPostgresIT {

    @Autowired private CreatePaymentHandler createPayment;
    @Autowired private CancelPaymentHandler cancelPayment;
    @Autowired private ManualReviewQueryPort manualReviews;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void cancellationCommitsPaymentSagaIdempotencyAndOutboxExactlyOnce() {
        var created = createPayment.handle(request()
                .key("create-cancel-" + UUID.randomUUID())
                .reference("ORDER-CANCEL-" + UUID.randomUUID())
                .build());
        UUID paymentId = created.payment().paymentId();
        String cancelKey = "cancel-" + UUID.randomUUID();
        var command = new CancelPaymentCommand(
                MERCHANT_ID,
                "integration-test-merchant",
                paymentId,
                cancelKey);

        CancelPaymentResult first = cancelPayment.handle(command);
        CancelPaymentResult replay = cancelPayment.handle(command);

        assertThat(first).isInstanceOf(CancelPaymentResult.Cancelled.class);
        assertThat(replay).isInstanceOf(CancelPaymentResult.Replayed.class);
        assertThat(jdbc.queryForObject(
                        "select status from payment.payments where id=?",
                        String.class,
                        paymentId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                        "select status from payment.payment_sagas where payment_id=?",
                        String.class,
                        paymentId))
                .isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject(
                        "select count(*) from payment.outbox_events"
                                + " where aggregate_id=? and event_type='payment.cancelled'",
                        Integer.class,
                        paymentId.toString()))
                .isEqualTo(1);
    }

    @Test
    void manualReviewQueryReturnsOnlySagasWhosePaymentAndSagaAreBothStopped() {
        var created = createPayment.handle(request()
                .key("create-review-" + UUID.randomUUID())
                .reference("ORDER-REVIEW-" + UUID.randomUUID())
                .build());
        UUID paymentId = created.payment().paymentId();
        jdbc.update(
                "update payment.payments set status='MANUAL_REVIEW_REQUIRED' where id=?",
                paymentId);
        jdbc.update(
                "update payment.payment_sagas set status='MANUAL_REVIEW_REQUIRED',"
                        + " last_error_code='RISK_REVIEW_REQUIRED' where payment_id=?",
                paymentId);

        var page = manualReviews.search(0, 100);

        assertThat(page.items())
                .filteredOn(item -> item.paymentId().equals(paymentId))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.merchantId()).isEqualTo(MERCHANT_ID);
                    assertThat(item.lastErrorCode()).isEqualTo("RISK_REVIEW_REQUIRED");
                    assertThat(item.reservationId()).isNull();
                    assertThat(item.journalId()).isNull();
                });
    }
}
