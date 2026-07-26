package com.payflow.observability;

import java.util.UUID;

/**
 * Correlation identity conventions shared by every PayFlow service.
 *
 * <p>The gateway creates or forwards the correlation id and every downstream service keeps it, so
 * the header name and MDC key must be identical everywhere. That is the only reason this lives in a
 * shared library instead of each service.
 *
 * <p>Incoming values are untrusted: a client controls the header. {@link #resolveOrGenerate(String)}
 * therefore rejects anything that could forge log lines or bloat log storage rather than passing it
 * through.
 */
public final class CorrelationId {

    /** Request/response header carrying the correlation id across HTTP boundaries. */
    public static final String HEADER = "X-Correlation-Id";

    /** SLF4J MDC key, so structured logs expose the id under a stable field name. */
    public static final String MDC_KEY = "correlationId";

    /**
     * Longest accepted incoming value. A correlation id is an identifier, not a payload; bounding it
     * keeps log volume predictable when a client sends something unreasonable.
     */
    public static final int MAX_LENGTH = 64;

    private CorrelationId() {
    }

    /** Creates a fresh correlation id. */
    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /**
     * Returns the incoming value when it is safe to propagate and log, otherwise a fresh id.
     *
     * <p>A malformed value is replaced rather than rejected with an error: losing the client's
     * chosen id is harmless, while failing the request would turn a cosmetic header problem into an
     * outage.
     *
     * @param incoming header value from the request, may be {@code null}
     * @return a value safe to place in a log line and forward downstream
     */
    public static String resolveOrGenerate(String incoming) {
        return isSafe(incoming) ? incoming : generate();
    }

    /**
     * Whether a value may be logged and forwarded as-is.
     *
     * <p>Only unreserved URL characters are allowed. This blocks CR/LF, which would otherwise let a
     * caller inject fabricated lines into structured logs, and blocks control characters that break
     * log parsers.
     */
    public static boolean isSafe(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isAllowed(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAllowed(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '-'
                || c == '_'
                || c == '.';
    }
}
