package com.payflow.merchant.application.port;

import com.payflow.merchant.domain.MerchantProfile;
import com.payflow.merchant.domain.MerchantStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;

public interface MerchantStore {
    MerchantProfile create(UUID id,String code,String name,BigDecimal feeRate,BigDecimal limit,Instant now);
    Optional<MerchantProfile> find(UUID id);
    Optional<PaymentPolicy> findPaymentPolicy(UUID id);
    MerchantProfile update(UUID id,String name,BigDecimal feeRate,BigDecimal limit,long expectedVersion,Instant now);
    MerchantProfile updateStatus(UUID id,MerchantStatus status,long expectedVersion,Instant now);
    Member saveMember(UUID id,UUID merchantId,String userId,String role,Instant now);
    boolean deactivateMember(UUID merchantId,UUID memberId);
    void insertApiKey(UUID id,UUID merchantId,String prefix,String hash,Instant expiresAt,Instant now);
    boolean revokeApiKey(UUID merchantId,UUID keyId,Instant now);
    UUID upsertWebhook(UUID id,UUID merchantId,String url,String encryptedSecret,Set<String> subscribedEvents,Instant now);
    Optional<WebhookConfiguration> findWebhook(UUID merchantId);
    void appendAudit(UUID id,String actor,String action,String resourceType,UUID resourceId,
            String beforeStatus,String afterStatus,String correlationId,Instant now);
    record WebhookConfiguration(UUID id,UUID merchantId,String url,String encryptedSecret,String subscribedEvents,boolean enabled) {}
    record Member(UUID id,UUID merchantId,String userId,String role,String status,Instant createdAt) {}
    record PaymentPolicy(UUID id,String status,String defaultCurrency,BigDecimal maxTransactionAmount,
            String feePolicyVersion,BigDecimal feeRate,String feeRoundingMode) {}
}
