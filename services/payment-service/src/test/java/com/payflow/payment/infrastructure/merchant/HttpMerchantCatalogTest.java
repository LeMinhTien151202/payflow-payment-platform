package com.payflow.payment.infrastructure.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.math.RoundingMode;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class HttpMerchantCatalogTest {

    @Test
    void mapsAuthenticatedRemotePolicyIntoImmutablePaymentSnapshot() {
        UUID merchantId = UUID.randomUUID();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var properties = new MerchantClientProperties(
                "remote",
                URI.create("http://merchant"),
                URI.create("http://identity/token"),
                "payflow-payment-internal",
                "client-secret",
                Duration.ofSeconds(2));
        var catalog = new HttpMerchantCatalog(
                builder.build(),
                properties,
                JsonMapper.builder().findAndAddModules().build(),
                Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC));
        server.expect(once(), requestTo("http://identity/token"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"access_token\":\"internal-token\",\"expires_in\":300}",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(
                        "http://merchant/internal/v1/merchants/" + merchantId + "/payment-policy"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer internal-token"))
                .andRespond(withSuccess(
                        "{\"id\":\"" + merchantId
                                + "\",\"status\":\"ACTIVE\",\"defaultCurrency\":\"VND\","
                                + "\"maxTransactionAmount\":50000000.0000,"
                                + "\"feePolicyVersion\":\"FEE_V7\",\"feeRate\":0.020000,"
                                + "\"feeRoundingMode\":\"HALF_UP\"}",
                        MediaType.APPLICATION_JSON));

        var snapshot = catalog.findById(merchantId).orElseThrow();

        assertThat(snapshot.id()).isEqualTo(merchantId);
        assertThat(snapshot.maxTransactionAmount().amount())
                .isEqualByComparingTo("50000000.0000");
        assertThat(snapshot.feePolicy().policyVersion()).isEqualTo("FEE_V7");
        assertThat(snapshot.feePolicy().rate()).isEqualByComparingTo("0.020000");
        assertThat(snapshot.feePolicy().roundingMode()).isEqualTo(RoundingMode.HALF_UP);
        server.verify();
    }
}
