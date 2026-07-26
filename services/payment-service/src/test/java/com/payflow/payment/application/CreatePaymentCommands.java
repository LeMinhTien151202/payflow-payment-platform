package com.payflow.payment.application;

import com.payflow.payment.application.command.CreatePaymentCommand;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Builds {@link CreatePaymentCommand} values for tests.
 *
 * <p>A builder rather than a nine-argument factory call. Most tests change one field and need the other
 * eight to be irrelevant; with positional arguments the field under test is invisible in the call, and a
 * reader cannot tell which value the assertion is actually about.
 *
 * <p>The ids match {@code db/seed}, so a test that later runs against a real database describes the same
 * merchant, customer, and account as this one does.
 */
public final class CreatePaymentCommands {

    /** The seeded ACTIVE merchant, limit 50,000,000 VND. */
    public static final UUID MERCHANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** A second merchant, used to prove that one merchant's idempotency keys never reach another's. */
    public static final UUID OTHER_MERCHANT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    public static final UUID CUSTOMER_ID = UUID.fromString("3beff442-7f10-4504-aab4-12d985cf3e95");
    public static final UUID SOURCE_ACCOUNT_ID = UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");

    public static final String IDEMPOTENCY_KEY = "d290f1ee-6c54-4b01-90e6-d701748f0851";
    public static final String MERCHANT_REFERENCE = "ORDER-2026-00001";

    private CreatePaymentCommands() {
    }

    /** A request that a seeded ACTIVE merchant would be allowed to make. */
    public static Builder request() {
        return new Builder();
    }

    public static final class Builder {

        private UUID merchantId = MERCHANT_ID;
        private String idempotencyKey = IDEMPOTENCY_KEY;
        private String merchantReference = MERCHANT_REFERENCE;
        private UUID customerId = CUSTOMER_ID;
        private UUID sourceAccountId = SOURCE_ACCOUNT_ID;
        private BigDecimal amount = new BigDecimal("500000");
        private String currency = "VND";
        private String description = "Thanh toán đơn hàng";
        private Map<String, String> metadata = Map.of("orderId", MERCHANT_REFERENCE);

        private Builder() {
        }

        public Builder merchant(UUID value) {
            this.merchantId = value;
            return this;
        }

        public Builder key(String value) {
            this.idempotencyKey = value;
            return this;
        }

        public Builder reference(String value) {
            this.merchantReference = value;
            return this;
        }

        public Builder customer(UUID value) {
            this.customerId = value;
            return this;
        }

        public Builder sourceAccount(UUID value) {
            this.sourceAccountId = value;
            return this;
        }

        /** Takes the string form so a test can pin the scale it means, which {@code double} cannot. */
        public Builder amount(String value) {
            this.amount = new BigDecimal(value);
            return this;
        }

        public Builder currency(String value) {
            this.currency = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder metadata(Map<String, String> value) {
            this.metadata = value;
            return this;
        }

        public CreatePaymentCommand build() {
            return new CreatePaymentCommand(
                    merchantId,
                    idempotencyKey,
                    merchantReference,
                    customerId,
                    sourceAccountId,
                    amount,
                    currency,
                    description,
                    metadata);
        }
    }
}
