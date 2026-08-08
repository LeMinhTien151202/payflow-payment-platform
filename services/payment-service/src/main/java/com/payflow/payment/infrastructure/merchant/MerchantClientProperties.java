package com.payflow.payment.infrastructure.merchant;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("payflow.merchant-client")
public record MerchantClientProperties(
        String mode,
        URI baseUri,
        URI tokenUri,
        String clientId,
        String clientSecret,
        Duration timeout) {

    public MerchantClientProperties {
        if (!"local".equals(mode) && !"remote".equals(mode)) {
            throw new IllegalArgumentException("Merchant client mode must be local or remote");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Merchant client timeout must be positive");
        }
    }
}
