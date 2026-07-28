package com.payflow.events.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class LedgerPaymentPostedDataTest {

    private static final UUID ID = UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");

    @Test
    void definesAndNormalizesTheV1Contract() {
        var data = new LedgerPaymentPostedData(ID, ID, new BigDecimal("500000"), "VND");

        assertThat(LedgerEvents.PAYMENT_POSTED.name()).isEqualTo("ledger.payment-posted");
        assertThat(LedgerEvents.PAYMENT_POSTED.version()).isEqualTo(1);
        assertThat(LedgerEvents.PAYMENT_POSTED.aggregateType()).isEqualTo("PAYMENT");
        assertThat(data.amount()).isEqualTo(new BigDecimal("500000.0000"));
    }

    @Test
    void rejectsInvalidMoney() {
        assertThatThrownBy(() -> new LedgerPaymentPostedData(ID, ID, BigDecimal.ZERO, "VND"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> new LedgerPaymentPostedData(ID, ID, BigDecimal.ONE, "usd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-4217");
    }

    @Test
    void serializesOnlyTheV1Fields() {
        var data = new LedgerPaymentPostedData(ID, ID, new BigDecimal("500000"), "VND");

        assertThat(JsonMapper.builder().build().valueToTree(data).propertyNames())
                .containsExactlyInAnyOrder("paymentId", "journalId", "amount", "currency");
    }

    @Test
    void postPaymentCommandCarriesOwnerReferencesWithoutLedgerInternalIds() {
        var command = new LedgerPostPaymentRequestedData(
                ID, UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("500000"), "VND");

        assertThat(LedgerEvents.POST_PAYMENT_REQUESTED.name())
                .isEqualTo("ledger.post-payment.requested");
        assertThat(JsonMapper.builder().build().valueToTree(command).propertyNames())
                .containsExactlyInAnyOrder(
                        "paymentId", "customerId", "merchantId", "amount", "currency");
        assertThat(command.amount()).isEqualTo(new BigDecimal("500000.0000"));
    }
}
