package com.payflow.events.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.payflow.events.EventEnvelope;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

class AccountFinancialEventsTest {

    private static final UUID PAYMENT_ID =
            UUID.fromString("c73e17b5-aaca-48da-9ed5-bb0937499f01");
    private static final UUID ACCOUNT_ID =
            UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final UUID RESERVATION_ID =
            UUID.fromString("41734b31-8e75-4570-bdc6-979fa02ab447");
    private static final Instant CAPTURED_AT = Instant.parse("2026-07-28T09:00:03Z");

    @Test
    void definesTheThreeAdr011Contracts() {
        assertThat(AccountEvents.FUNDS_RESERVED.name()).isEqualTo("account.funds-reserved");
        assertThat(AccountEvents.CAPTURE_REQUESTED.name()).isEqualTo("account.capture.requested");
        assertThat(AccountEvents.FUNDS_CAPTURED.name()).isEqualTo("account.funds-captured");
        assertThat(AccountEvents.FUNDS_CAPTURED.version()).isEqualTo(1);
        assertThat(AccountEvents.FUNDS_CAPTURED.aggregateType()).isEqualTo("PAYMENT");
    }

    @Test
    void normalizesAmountsForEveryAccountPayload() {
        assertThat(reserved("500000", "VND").amount()).isEqualTo(new BigDecimal("500000.0000"));
        assertThat(new AccountCaptureRequestedData(
                        PAYMENT_ID, ACCOUNT_ID, RESERVATION_ID, new BigDecimal("1"), "VND")
                .amount()).isEqualTo(new BigDecimal("1.0000"));
        assertThat(captured("1", "VND", RESERVATION_ID).amount())
                .isEqualTo(new BigDecimal("1.0000"));
    }

    @Test
    void rejectsInvalidMoneyAndMissingIdentity() {
        assertThatThrownBy(() -> reserved("0", "VND"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> reserved("1.00001", "VND"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scale");
        assertThatThrownBy(() -> reserved("1", "vnd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO-4217");
        assertThatThrownBy(() -> new AccountFundsReservedData(
                        PAYMENT_ID, ACCOUNT_ID, null, BigDecimal.ONE, "VND"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("reservationId");
    }

    @Test
    void capturedEnvelopeHasTheExactV1PayloadAndRoundTrips() {
        var data = captured("500000", "VND", RESERVATION_ID);
        var expected = EventEnvelope.of(
                UUID.fromString("61734b31-8e75-4570-bdc6-979fa02ab448"),
                AccountEvents.FUNDS_CAPTURED,
                PAYMENT_ID.toString(),
                "0a1b2c3d-4e5f-6789-abcd-ef0123456789",
                "account-ledger-service",
                CAPTURED_AT,
                data);
        JsonMapper mapper = JsonMapper.builder()
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();

        String json = mapper.writeValueAsString(expected);
        assertThat(mapper.readTree(json).get("data").propertyNames())
                .containsExactlyInAnyOrder(
                        "paymentId", "accountId", "reservationId", "amount", "currency", "capturedAt");
        EventEnvelope<AccountFundsCapturedData> actual = mapper.readValue(
                json, new TypeReference<EventEnvelope<AccountFundsCapturedData>>() {});
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void reservedAndCaptureRequestExposeOnlyTheV1Fields() {
        JsonMapper mapper = JsonMapper.builder().build();

        assertThat(mapper.valueToTree(reserved("500000", "VND")).propertyNames())
                .containsExactlyInAnyOrder(
                        "paymentId", "accountId", "reservationId", "amount", "currency");
        assertThat(mapper.valueToTree(new AccountCaptureRequestedData(
                                PAYMENT_ID,
                                ACCOUNT_ID,
                                RESERVATION_ID,
                                new BigDecimal("500000"),
                                "VND"))
                        .propertyNames())
                .containsExactlyInAnyOrder(
                        "paymentId", "accountId", "reservationId", "amount", "currency");
    }

    private static AccountFundsReservedData reserved(String amount, String currency) {
        return new AccountFundsReservedData(
                PAYMENT_ID, ACCOUNT_ID, RESERVATION_ID, new BigDecimal(amount), currency);
    }

    private static AccountFundsCapturedData captured(
            String amount, String currency, UUID reservationId) {
        return new AccountFundsCapturedData(
                PAYMENT_ID,
                ACCOUNT_ID,
                reservationId,
                new BigDecimal(amount),
                currency,
                CAPTURED_AT);
    }
}
