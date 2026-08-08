package com.payflow.account.infrastructure.messaging;

/** Unique for one JVM lifetime, as required by ADR-014. */
record OutboxPublisherOwner(String value) {}
