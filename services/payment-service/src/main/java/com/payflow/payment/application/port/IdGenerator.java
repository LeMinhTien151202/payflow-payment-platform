package com.payflow.payment.application.port;

import java.util.UUID;

/**
 * Generates the identifiers a use case assigns.
 *
 * <p>A port for one line of code, for one reason: a payment id is returned to the caller and stored in an
 * idempotency record, so a test that wants to say which payment id it expects needs to be able to fix it.
 * Calling {@code UUID.randomUUID()} in the handler would make every assertion about identity read the value
 * back from the thing it is trying to check.
 *
 * <p>Only for identifiers the application chooses. Surrogate keys that never leave the database — a status
 * history row id, for instance — are the adapter's business.
 */
public interface IdGenerator {

    UUID newId();
}
