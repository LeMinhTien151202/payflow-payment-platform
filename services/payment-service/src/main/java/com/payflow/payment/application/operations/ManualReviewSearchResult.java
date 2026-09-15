package com.payflow.payment.application.operations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Oldest-first bounded queue for payment operations. */
@Schema(description = "A bounded oldest-first page of manual-review work")
public record ManualReviewSearchResult(
        List<ManualReviewItem> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public ManualReviewSearchResult {
        items = List.copyOf(items);
    }

    public static ManualReviewSearchResult of(
            List<ManualReviewItem> items, int page, int size, long totalElements) {
        long pages = totalElements == 0 ? 0 : 1 + ((totalElements - 1) / size);
        if (pages > Integer.MAX_VALUE) {
            throw new IllegalStateException("manual review page count exceeds the API range");
        }
        return new ManualReviewSearchResult(
                items, page, size, totalElements, (int) pages);
    }
}
