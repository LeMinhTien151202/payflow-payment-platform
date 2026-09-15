package com.payflow.payment.application.operations;

import java.util.List;

/** Persistence result kept separate from the public pagination envelope. */
public record ManualReviewPage(List<ManualReviewItem> items, long totalElements) {

    public ManualReviewPage {
        items = List.copyOf(items);
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements cannot be negative");
        }
    }
}
