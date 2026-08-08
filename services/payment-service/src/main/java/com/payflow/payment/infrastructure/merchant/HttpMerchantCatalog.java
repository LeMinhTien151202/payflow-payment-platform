package com.payflow.payment.infrastructure.merchant;

import com.payflow.payment.application.exception.MerchantCatalogUnavailableException;
import com.payflow.payment.application.port.MerchantCatalog;
import com.payflow.payment.domain.model.FeePolicySnapshot;
import com.payflow.payment.domain.model.MerchantSnapshot;
import com.payflow.payment.domain.model.MerchantStatus;
import com.payflow.payment.domain.model.Money;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Phase 2 adapter: reads Merchant-owned policy over authenticated REST, never across databases. */
@Component
@ConditionalOnProperty(name = "payflow.merchant-client.mode", havingValue = "remote")
final class HttpMerchantCatalog implements MerchantCatalog {
    private final RestClient rest;
    private final MerchantClientProperties properties;
    private final MerchantServiceTokenProvider tokens;

    @Autowired
    HttpMerchantCatalog(
            RestClient.Builder builder,
            MerchantClientProperties properties,
            ObjectMapper json,
            Clock clock) {
        this(buildClient(builder, properties), properties, json, clock);
    }

    HttpMerchantCatalog(
            RestClient rest,
            MerchantClientProperties properties,
            ObjectMapper json,
            Clock clock) {
        this.rest = rest;
        this.properties = properties;
        this.tokens = new MerchantServiceTokenProvider(rest, properties, json, clock);
    }

    private static RestClient buildClient(
            RestClient.Builder builder, MerchantClientProperties properties) {
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(properties.timeout());
        requests.setReadTimeout(properties.timeout());
        return builder.requestFactory(requests).build();
    }

    @Override
    public Optional<MerchantSnapshot> findById(UUID merchantId) {
        try {
            PolicyResponse response = rest.get()
                    .uri(properties.baseUri().resolve(
                            "/internal/v1/merchants/" + merchantId + "/payment-policy"))
                    .header("Authorization", "Bearer " + tokens.token())
                    .retrieve()
                    .body(PolicyResponse.class);
            if (response == null) {
                throw new IllegalStateException("empty merchant policy response");
            }
            return Optional.of(response.toSnapshot());
        } catch (HttpStatusCodeException failure) {
            if (failure.getStatusCode().value() == 404) {
                return Optional.empty();
            }
            throw new MerchantCatalogUnavailableException(failure);
        } catch (RuntimeException failure) {
            if (failure instanceof MerchantCatalogUnavailableException unavailable) {
                throw unavailable;
            }
            throw new MerchantCatalogUnavailableException(failure);
        }
    }

    record PolicyResponse(
            UUID id,
            String status,
            String defaultCurrency,
            BigDecimal maxTransactionAmount,
            String feePolicyVersion,
            BigDecimal feeRate,
            String feeRoundingMode) {
        MerchantSnapshot toSnapshot() {
            return new MerchantSnapshot(
                    id,
                    MerchantStatus.valueOf(status),
                    defaultCurrency,
                    new Money(maxTransactionAmount, defaultCurrency),
                    new FeePolicySnapshot(
                            feePolicyVersion,
                            feeRate,
                            java.math.RoundingMode.valueOf(feeRoundingMode)));
        }
    }
}
