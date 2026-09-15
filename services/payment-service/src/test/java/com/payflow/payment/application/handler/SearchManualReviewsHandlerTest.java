package com.payflow.payment.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.payflow.payment.application.operations.ManualReviewItem;
import com.payflow.payment.application.operations.ManualReviewPage;
import com.payflow.payment.application.port.ManualReviewQueryPort;
import com.payflow.payment.domain.model.PaymentSagaStep;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SearchManualReviewsHandlerTest {

    private final ManualReviewQueryPort query = mock(ManualReviewQueryPort.class);
    private final SearchManualReviewsHandler handler = new SearchManualReviewsHandler(query);

    @Test
    void returnsAStablePaginationEnvelope() {
        ManualReviewItem item = new ManualReviewItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("500000.0000"),
                "VND",
                PaymentSagaStep.RISK_ASSESSMENT,
                0,
                "RISK_REVIEW_REQUIRED",
                null,
                null,
                Instant.parse("2026-09-10T10:00:00Z"));
        when(query.search(1, 20)).thenReturn(new ManualReviewPage(List.of(item), 41));

        var result = handler.handle(1, 20);

        assertThat(result.items()).containsExactly(item);
        assertThat(result.totalElements()).isEqualTo(41);
        assertThat(result.totalPages()).isEqualTo(3);
        verify(query).search(1, 20);
    }

    @Test
    void refusesUnboundedPagesBeforePersistence() {
        assertThatThrownBy(() -> handler.handle(-1, 101))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(query);
    }
}
