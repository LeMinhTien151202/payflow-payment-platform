package com.payflow.merchant.api;

import com.payflow.merchant.application.MerchantActor;
import com.payflow.merchant.application.MerchantApplicationService;
import com.payflow.merchant.domain.MerchantProfile;
import com.payflow.merchant.domain.MerchantStatus;
import com.payflow.merchant.application.port.MerchantStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/merchants")
@Tag(name="Merchants",description="Merchant profile, lifecycle, members, API keys and webhook configuration")
public class MerchantController {
    private final MerchantApplicationService service;
    public MerchantController(MerchantApplicationService service){this.service=service;}
    @Operation(summary="Create a merchant",description="Operations creates a PENDING merchant with an immutable code and initial fee/limit policy.")
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    MerchantProfile create(@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="X-Correlation-Id",required=false) String correlation,
            @Valid @RequestBody CreateMerchantRequest request){
        return service.create(actor(jwt),request.code(),request.name(),request.feeRate(),request.maxTransactionAmount(),correlation(correlation));
    }
    @Operation(summary="Read merchant profile",description="Returns only the caller-owned merchant unless a global scope is present.")
    @GetMapping("/{merchantId}")
    MerchantProfile get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID merchantId){return service.get(actor(jwt),merchantId);}
    @Operation(summary="Update merchant policy",description="Optimistic versioning prevents lost updates to name, fee rate and transaction limit.")
    @PutMapping("/{merchantId}")
    MerchantProfile update(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID merchantId,
            @RequestHeader(value="X-Correlation-Id",required=false) String correlation,
            @Valid @RequestBody UpdateMerchantRequest request){
        return service.update(actor(jwt),merchantId,request.name(),request.feeRate(),
                request.maxTransactionAmount(),request.expectedVersion(),correlation(correlation));
    }
    @Operation(summary="Change merchant status",description="Operations-only state transition across PENDING, ACTIVE, SUSPENDED and CLOSED.")
    @PutMapping("/{merchantId}/status")
    MerchantProfile status(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID merchantId,
            @RequestHeader(value="X-Correlation-Id",required=false) String correlation,
            @Valid @RequestBody ChangeStatusRequest request){
        return service.changeStatus(actor(jwt),merchantId,request.status(),request.expectedVersion(),
                correlation(correlation));
    }
    @Operation(summary="Add or update a member",description="Associates a Keycloak subject with MERCHANT_ADMIN or MERCHANT_USER for this merchant.")
    @PutMapping("/{merchantId}/members/{userId}")
    MerchantStore.Member member(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID merchantId,
            @PathVariable @Size(max=100) String userId,
            @RequestHeader(value="X-Correlation-Id",required=false) String correlation,
            @Valid @RequestBody MemberRequest request){
        return service.saveMember(actor(jwt),merchantId,userId,request.role(),correlation(correlation));
    }
    @Operation(summary="Deactivate a member",description="Soft-deactivates membership and appends an audit fact.")
    @DeleteMapping("/{merchantId}/members/{memberId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void deactivateMember(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID merchantId,
            @PathVariable UUID memberId,
            @RequestHeader(value="X-Correlation-Id",required=false) String correlation){
        service.deactivateMember(actor(jwt),merchantId,memberId,correlation(correlation));
    }
    @Operation(summary="Create an API key",description="Returns plaintext exactly once; only a BCrypt hash is persisted.")
    @PostMapping("/{merchantId}/api-keys") @ResponseStatus(HttpStatus.CREATED)
    MerchantApplicationService.ApiKeyCreated apiKey(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID merchantId,
            @RequestHeader(value="X-Correlation-Id",required=false) String correlation,@Valid @RequestBody ApiKeyRequest request){
        return service.createApiKey(actor(jwt),merchantId,request.expiresAt(),correlation(correlation));
    }
    @Operation(summary="Revoke an API key",description="Idempotently prevents future use without deleting audit history.")
    @DeleteMapping("/{merchantId}/api-keys/{keyId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID merchantId,@PathVariable UUID keyId,
            @RequestHeader(value="X-Correlation-Id",required=false) String correlation){
        service.revokeApiKey(actor(jwt),merchantId,keyId,correlation(correlation));
    }
    @Operation(summary="Configure merchant webhook",description="Rotates the HMAC secret, encrypts it at rest and stores the subscribed event list.")
    @PutMapping("/{merchantId}/webhook")
    MerchantApplicationService.WebhookCreated webhook(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID merchantId,
            @RequestHeader(value="X-Correlation-Id",required=false) String correlation,@Valid @RequestBody WebhookRequest request){
        return service.configureWebhook(actor(jwt),merchantId,request.url(),request.subscribedEvents(),correlation(correlation));
    }
    private static MerchantActor actor(Jwt jwt){
        UUID merchantId=null; String claim=jwt.getClaimAsString("merchant_id");
        if(claim!=null&&!claim.isBlank()) merchantId=UUID.fromString(claim);
        Set<String> scopes=Arrays.stream(jwt.getClaimAsString("scope")==null?new String[0]:jwt.getClaimAsString("scope").split(" "))
          .filter(s->!s.isBlank()).collect(Collectors.toUnmodifiableSet());
        return new MerchantActor(jwt.getSubject(),merchantId,scopes);
    }
    private static String correlation(String value){return value==null||value.isBlank()?UUID.randomUUID().toString():value;}
    record CreateMerchantRequest(@NotBlank @Pattern(regexp="[A-Z0-9_-]{3,50}") String code,
      @NotBlank @Size(max=200) String name,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal feeRate,
      @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal maxTransactionAmount){}
    record ApiKeyRequest(@NotNull @Future Instant expiresAt){}
    record UpdateMerchantRequest(@NotBlank @Size(max=200) String name,
      @NotNull @DecimalMin("0") @DecimalMax("1") BigDecimal feeRate,
      @NotNull @DecimalMin(value="0",inclusive=false) BigDecimal maxTransactionAmount,
      @NotNull @DecimalMin("0") Long expectedVersion){}
    record ChangeStatusRequest(@NotNull MerchantStatus status,
      @NotNull @DecimalMin("0") Long expectedVersion){}
    record MemberRequest(@NotBlank @Pattern(regexp="MERCHANT_(ADMIN|USER)") String role){}
    record WebhookRequest(@NotBlank @Pattern(regexp="https?://.+") @Size(max=1000) String url,
      @NotNull @Size(min=1,max=4) Set<@Pattern(regexp="(payment|refund)\\.(succeeded|failed)") String> subscribedEvents){}
}
