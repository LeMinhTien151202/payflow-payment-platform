package com.payflow.payment.application.handler;

import com.payflow.payment.application.operations.ManualReviewPage;
import com.payflow.payment.application.operations.ManualReviewSearchResult;
import com.payflow.payment.application.port.ManualReviewQueryPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lists durable manual-review items without locking or changing their Sagas. */
@Service
public class SearchManualReviewsHandler {

    private final ManualReviewQueryPort reviews;

    public SearchManualReviewsHandler(ManualReviewQueryPort reviews) {
        this.reviews = reviews;
    }

    @Transactional(readOnly = true)
    public ManualReviewSearchResult handle(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "manual review page must be non-negative and size must be between 1 and 100");
        }
        ManualReviewPage result = reviews.search(page, size);
        return ManualReviewSearchResult.of(
                result.items(), page, size, result.totalElements());
    }
}
