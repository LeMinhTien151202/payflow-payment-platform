package com.payflow.payment.application.port;

import com.payflow.payment.application.operations.ManualReviewPage;

/** Read-only operations queue; state changes remain in ResolveManualReviewHandler. */
public interface ManualReviewQueryPort {

    ManualReviewPage search(int page, int size);
}
