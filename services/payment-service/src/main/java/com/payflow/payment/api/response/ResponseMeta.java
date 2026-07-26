package com.payflow.payment.api.response;

import java.time.Instant;

/** Request-scoped metadata returned by successful PayFlow APIs. */
public record ResponseMeta(String correlationId, Instant timestamp) {
}
