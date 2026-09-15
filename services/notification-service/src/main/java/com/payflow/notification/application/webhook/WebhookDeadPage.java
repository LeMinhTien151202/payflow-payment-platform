package com.payflow.notification.application.webhook;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Oldest-first bounded page of terminal webhook deliveries. */
@Schema(description = "A bounded oldest-first page of DEAD webhook deliveries")
public record WebhookDeadPage(
        List<WebhookDeadItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public WebhookDeadPage {
        items = List.copyOf(items);
        if (page < 0 || size < 1 || size > 100 || totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("invalid webhook dead-letter pagination");
        }
    }

    public static WebhookDeadPage of(
            List<WebhookDeadItem> items, int page, int size, long totalElements) {
        if (page < 0 || size < 1 || size > 100 || totalElements < 0) {
            throw new IllegalArgumentException("invalid webhook dead-letter pagination");
        }
        long pages = totalElements == 0 ? 0 : 1 + ((totalElements - 1) / size);
        if (pages > Integer.MAX_VALUE) {
            throw new IllegalStateException("webhook dead-letter page count exceeds the API range");
        }
        return new WebhookDeadPage(items, page, size, totalElements, (int) pages);
    }
}
