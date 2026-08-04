package com.payflow.payment.application;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** One bounded page of merchant-owned payments. */
@Schema(description = "A bounded page of payments owned by the authenticated merchant")
public record PaymentSearchResult(
        @Schema(description = "Payments in deterministic newest-first order")
                List<PaymentDetail> items,
        @Schema(description = "Zero-based page number", example = "0") int page,
        @Schema(description = "Requested page size", example = "20") int size,
        @Schema(description = "Matching payment count", example = "37") long totalElements,
        @Schema(description = "Number of pages", example = "2") int totalPages) {

    public PaymentSearchResult {
        items = List.copyOf(items);
        if (page < 0 || size < 1 || totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("payment search result pagination is invalid");
        }
    }

    public static PaymentSearchResult of(
            List<PaymentDetail> items, int page, int size, long totalElements) {
        long pages = totalElements == 0 ? 0 : 1 + ((totalElements - 1) / size);
        if (pages > Integer.MAX_VALUE) {
            throw new IllegalStateException("payment search page count exceeds the API range");
        }
        return new PaymentSearchResult(items, page, size, totalElements, (int) pages);
    }
}
