package com.payflow.merchant.application;

import java.util.Set;
import java.util.UUID;

public record MerchantActor(String subject, UUID merchantId, Set<String> scopes) {
    public boolean has(String scope) { return scopes.contains(scope); }
    public void requireWriteAccess(UUID resourceMerchantId) {
        requireAccess(resourceMerchantId);
        if (!has("merchant:write") && !has("merchant:write:any")) {
            throw new MerchantException(
                    "MERCHANT_ACCESS_DENIED", "Merchant write scope is required", 403);
        }
    }
    public void requireAccess(UUID resourceMerchantId) {
        if (!has("merchant:read:any") && !has("merchant:write:any")
                && (merchantId == null || !merchantId.equals(resourceMerchantId))) {
            throw new MerchantException("MERCHANT_ACCESS_DENIED", "Merchant ownership does not match", 403);
        }
    }
}
