package com.payflow.payment.application.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.payflow.payment.application.PaymentSearchQuery;
import com.payflow.payment.application.PaymentSearchResult;
import com.payflow.payment.application.port.PaymentSearchPage;
import com.payflow.payment.application.port.PaymentSearchPort;
import com.payflow.payment.domain.model.MerchantSnapshot;
import com.payflow.payment.domain.model.MerchantStatus;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchPaymentsHandlerTest {

    @Mock
    private PaymentSearchPort payments;

    @Test
    @DisplayName("maps the bounded persistence page without changing merchant criteria")
    void mapsSearchPage() {
        UUID merchantId = UUID.randomUUID();
        PaymentSearchQuery query =
                new PaymentSearchQuery(merchantId, PaymentStatus.SUCCEEDED, null, null, 0, 20);
        Payment payment = payment(merchantId);
        given(payments.search(query)).willReturn(new PaymentSearchPage(List.of(payment), 21));

        PaymentSearchResult result = new SearchPaymentsHandler(payments).handle(query);

        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.paymentId()).isEqualTo(payment.id());
            assertThat(item.merchantId()).isEqualTo(merchantId);
        });
        assertThat(result.totalElements()).isEqualTo(21);
        assertThat(result.totalPages()).isEqualTo(2);
        verify(payments).search(query);
    }

    private static Payment payment(UUID merchantId) {
        Instant now = Instant.parse("2026-07-26T09:15:00Z");
        UUID paymentId = UUID.randomUUID();
        MerchantSnapshot merchant = MerchantSnapshot.legacyNoFee(
                merchantId, MerchantStatus.ACTIVE, "VND", Money.of("1000.0000", "VND"));
        Payment created = Payment.create(
                merchant,
                new PaymentIntake(
                        paymentId,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "ORDER-SEARCH-1",
                        "KEY-SEARCH-1",
                        Money.of("100.0000", "VND"),
                        "Search fixture",
                        Map.of(),
                        now));
        return created;
    }
}
