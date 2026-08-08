package com.payflow.merchant.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.payflow.merchant.application.port.MerchantStore;
import com.payflow.merchant.domain.MerchantProfile;
import com.payflow.merchant.domain.MerchantStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MerchantApplicationServiceTest {

    private MerchantStore store;
    private MerchantApplicationService service;
    private final Instant now = Instant.parse("2026-08-07T00:00:00Z");

    @BeforeEach
    void setUp() {
        store = mock(MerchantStore.class);
        service = new MerchantApplicationService(
                store,
                mock(SecretMaterial.class),
                mock(SecretCipher.class),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void readScopeCannotMutateMerchantProfile() {
        UUID merchantId = UUID.randomUUID();
        var actor = new MerchantActor("subject", merchantId, Set.of("merchant:read"));

        assertThatThrownBy(() -> service.update(
                        actor,
                        merchantId,
                        "Name",
                        new BigDecimal("0.01"),
                        new BigDecimal("1000.0000"),
                        0,
                        "corr"))
                .isInstanceOf(MerchantException.class)
                .extracting(error -> ((MerchantException) error).code())
                .isEqualTo("MERCHANT_ACCESS_DENIED");
    }

    @Test
    void closedMerchantCannotBeReactivated() {
        UUID merchantId = UUID.randomUUID();
        when(store.find(merchantId)).thenReturn(Optional.of(profile(merchantId, MerchantStatus.CLOSED, 3)));
        var actor = new MerchantActor("operations", null, Set.of("merchant:write:any"));

        assertThatThrownBy(() -> service.changeStatus(
                        actor, merchantId, MerchantStatus.ACTIVE, 3, "corr"))
                .isInstanceOf(MerchantException.class)
                .extracting(error -> ((MerchantException) error).code())
                .isEqualTo("MERCHANT_STATUS_TRANSITION_INVALID");
    }

    @Test
    void operationsCanActivatePendingMerchantWithOptimisticVersion() {
        UUID merchantId = UUID.randomUUID();
        var before = profile(merchantId, MerchantStatus.PENDING, 0);
        var after = profile(merchantId, MerchantStatus.ACTIVE, 1);
        when(store.find(merchantId)).thenReturn(Optional.of(before));
        when(store.updateStatus(merchantId, MerchantStatus.ACTIVE, 0, now)).thenReturn(after);
        var actor = new MerchantActor("operations", null, Set.of("merchant:write:any"));

        service.changeStatus(actor, merchantId, MerchantStatus.ACTIVE, 0, "corr");

        verify(store).updateStatus(merchantId, MerchantStatus.ACTIVE, 0, now);
        verify(store).appendAudit(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("operations"),
                org.mockito.ArgumentMatchers.eq("MERCHANT_STATUS_CHANGED"),
                org.mockito.ArgumentMatchers.eq("MERCHANT"),
                org.mockito.ArgumentMatchers.eq(merchantId),
                org.mockito.ArgumentMatchers.eq("PENDING"),
                org.mockito.ArgumentMatchers.eq("ACTIVE"),
                org.mockito.ArgumentMatchers.eq("corr"),
                org.mockito.ArgumentMatchers.eq(now));
    }

    private MerchantProfile profile(UUID id, MerchantStatus status, long version) {
        return new MerchantProfile(
                id,
                "SHOP_01",
                "Merchant",
                status,
                "VND",
                new BigDecimal("0.010000"),
                new BigDecimal("1000.0000"),
                now,
                now,
                version);
    }
}
