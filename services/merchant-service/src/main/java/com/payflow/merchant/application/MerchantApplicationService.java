package com.payflow.merchant.application;

import com.payflow.merchant.application.port.MerchantStore;
import com.payflow.merchant.domain.MerchantProfile;
import com.payflow.merchant.domain.MerchantStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantApplicationService {
    private final MerchantStore store; private final SecretMaterial secrets; private final SecretCipher cipher; private final Clock clock;
    public MerchantApplicationService(MerchantStore store,SecretMaterial secrets,SecretCipher cipher,Clock clock) {
        this.store=store; this.secrets=secrets; this.cipher=cipher; this.clock=clock;
    }
    @Transactional
    public MerchantProfile create(MerchantActor actor,String code,String name,BigDecimal feeRate,BigDecimal limit,String correlationId) {
        if (!actor.has("merchant:write:any")) throw new MerchantException("MERCHANT_ACCESS_DENIED","Global merchant write scope is required",403);
        Instant now=clock.instant(); UUID id=UUID.randomUUID();
        MerchantProfile profile=store.create(id,code,name,feeRate,limit,now);
        store.appendAudit(UUID.randomUUID(),actor.subject(),"MERCHANT_CREATED","MERCHANT",id,null,profile.status().name(),correlationId,now);
        return profile;
    }
    @Transactional(readOnly=true)
    public MerchantProfile get(MerchantActor actor,UUID id) {
        actor.requireAccess(id);
        return store.find(id).orElseThrow(()->new MerchantException("MERCHANT_NOT_FOUND","Merchant was not found",404));
    }
    @Transactional
    public MerchantProfile update(MerchantActor actor,UUID id,String name,BigDecimal feeRate,
            BigDecimal limit,long expectedVersion,String correlationId) {
        actor.requireWriteAccess(id); MerchantProfile before=required(id); Instant now=clock.instant();
        MerchantProfile after=store.update(id,name,feeRate,limit,expectedVersion,now);
        store.appendAudit(UUID.randomUUID(),actor.subject(),"MERCHANT_PROFILE_UPDATED","MERCHANT",id,
                before.status().name(),after.status().name(),correlationId,now);
        return after;
    }
    @Transactional
    public MerchantProfile changeStatus(MerchantActor actor,UUID id,MerchantStatus target,
            long expectedVersion,String correlationId) {
        if(!actor.has("merchant:write:any")) throw new MerchantException(
                "MERCHANT_ACCESS_DENIED","Global merchant write scope is required",403);
        MerchantProfile before=required(id); requireTransition(before.status(),target); Instant now=clock.instant();
        MerchantProfile after=store.updateStatus(id,target,expectedVersion,now);
        store.appendAudit(UUID.randomUUID(),actor.subject(),"MERCHANT_STATUS_CHANGED","MERCHANT",id,
                before.status().name(),after.status().name(),correlationId,now);
        return after;
    }
    @Transactional
    public MerchantStore.Member saveMember(MerchantActor actor,UUID merchantId,String userId,String role,
            String correlationId) {
        actor.requireWriteAccess(merchantId); required(merchantId); Instant now=clock.instant();
        var member=store.saveMember(UUID.randomUUID(),merchantId,userId,role,now);
        store.appendAudit(UUID.randomUUID(),actor.subject(),"MERCHANT_MEMBER_SAVED","MERCHANT_MEMBER",
                member.id(),null,"ACTIVE",correlationId,now);
        return member;
    }
    @Transactional
    public boolean deactivateMember(MerchantActor actor,UUID merchantId,UUID memberId,String correlationId) {
        actor.requireWriteAccess(merchantId); Instant now=clock.instant();
        boolean changed=store.deactivateMember(merchantId,memberId);
        if(changed) store.appendAudit(UUID.randomUUID(),actor.subject(),"MERCHANT_MEMBER_DEACTIVATED",
                "MERCHANT_MEMBER",memberId,"ACTIVE","INACTIVE",correlationId,now);
        return changed;
    }
    @Transactional
    public ApiKeyCreated createApiKey(MerchantActor actor,UUID merchantId,Instant expiresAt,String correlationId) {
        actor.requireWriteAccess(merchantId); requireConfigurable(required(merchantId));
        String plain=secrets.generate("pfk"); String prefix=plain.substring(0,Math.min(16,plain.length())); UUID id=UUID.randomUUID(); Instant now=clock.instant();
        store.insertApiKey(id,merchantId,prefix,secrets.hash(plain),expiresAt,now);
        store.appendAudit(UUID.randomUUID(),actor.subject(),"API_KEY_CREATED","MERCHANT_API_KEY",id,null,"ACTIVE",correlationId,now);
        return new ApiKeyCreated(id,prefix,plain,expiresAt);
    }
    @Transactional
    public boolean revokeApiKey(MerchantActor actor,UUID merchantId,UUID keyId,String correlationId) {
        actor.requireWriteAccess(merchantId); Instant now=clock.instant(); boolean changed=store.revokeApiKey(merchantId,keyId,now);
        if (changed) store.appendAudit(UUID.randomUUID(),actor.subject(),"API_KEY_REVOKED","MERCHANT_API_KEY",keyId,"ACTIVE","REVOKED",correlationId,now);
        return changed;
    }
    @Transactional
    public WebhookCreated configureWebhook(MerchantActor actor,UUID merchantId,String url,Set<String> subscribedEvents,String correlationId) {
        actor.requireWriteAccess(merchantId); requireConfigurable(required(merchantId)); Instant now=clock.instant(); String plain=secrets.generate("whsec"); UUID id=UUID.randomUUID();
        var allowed=Set.of("payment.succeeded","payment.failed","refund.succeeded","refund.failed");
        if(subscribedEvents==null||subscribedEvents.isEmpty()||!allowed.containsAll(subscribedEvents))
            throw new MerchantException("MERCHANT_WEBHOOK_EVENTS_INVALID","Webhook event subscription is invalid",400);
        store.upsertWebhook(id,merchantId,url,cipher.encrypt(plain),subscribedEvents,now);
        store.appendAudit(UUID.randomUUID(),actor.subject(),"WEBHOOK_CONFIGURED","MERCHANT_WEBHOOK",id,null,"ENABLED",correlationId,now);
        return new WebhookCreated(id,url,plain);
    }
    @Transactional(readOnly=true)
    public InternalWebhook getWebhook(UUID merchantId) {
        var w=store.findWebhook(merchantId).orElseThrow(()->new MerchantException("MERCHANT_WEBHOOK_NOT_FOUND","Merchant webhook was not found",404));
        return new InternalWebhook(w.id(),w.merchantId(),w.url(),cipher.decrypt(w.encryptedSecret()),w.subscribedEvents(),w.enabled());
    }
    @Transactional(readOnly=true)
    public MerchantStore.PaymentPolicy getPaymentPolicy(UUID merchantId) {
        return store.findPaymentPolicy(merchantId).orElseThrow(()->new MerchantException(
                "MERCHANT_NOT_FOUND","Merchant was not found",404));
    }
    private MerchantProfile required(UUID id){return store.find(id).orElseThrow(()->new MerchantException(
            "MERCHANT_NOT_FOUND","Merchant was not found",404));}
    private static void requireConfigurable(MerchantProfile profile){
        if(profile.status()==MerchantStatus.CLOSED) throw new MerchantException(
                "MERCHANT_CLOSED","Closed merchant configuration cannot be changed",409);
    }
    private static void requireTransition(MerchantStatus from,MerchantStatus to){
        boolean valid=from!=to&&switch(from){
            case PENDING -> to==MerchantStatus.ACTIVE||to==MerchantStatus.CLOSED;
            case ACTIVE -> to==MerchantStatus.SUSPENDED||to==MerchantStatus.CLOSED;
            case SUSPENDED -> to==MerchantStatus.ACTIVE||to==MerchantStatus.CLOSED;
            case CLOSED -> false;
        };
        if(!valid) throw new MerchantException("MERCHANT_STATUS_TRANSITION_INVALID",
                "Merchant status transition is not allowed",409);
    }
    public record ApiKeyCreated(UUID id,String prefix,String plaintextKey,Instant expiresAt) {}
    public record WebhookCreated(UUID id,String url,String signingSecret) {}
    public record InternalWebhook(UUID id,UUID merchantId,String url,String signingSecret,String subscribedEvents,boolean enabled) {}
}
