package com.payflow.settlement.api;
import com.payflow.settlement.application.*;
import io.swagger.v3.oas.annotations.Operation; import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate; import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal; import org.springframework.security.oauth2.jwt.Jwt; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/settlements") @Tag(name="Settlements",description="Merchant-scoped daily settlement read model")
class SettlementController {
 private final SettlementQueryService queries; SettlementController(SettlementQueryService q){queries=q;}
 @GetMapping @Operation(summary="List merchant settlement batches",description="Returns immutable daily totals for the merchant identity carried by the JWT; no merchant id is trusted from query input.")
 PageResult<SettlementBatchView> list(@AuthenticationPrincipal Jwt jwt,@RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return queries.batches(merchant(jwt),from,to,page,size);}
 @GetMapping("/{id}") @Operation(summary="Get a settlement batch",description="Returns the batch and its immutable payment/refund contributions after enforcing merchant ownership.")
 SettlementQueryService.SettlementBatchDetail get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return queries.batch(merchant(jwt),id);}
 private static UUID merchant(Jwt jwt){String v=jwt.getClaimAsString("merchant_id");if(v==null||v.isBlank())throw new SettlementAccessException("SETTLEMENT_MERCHANT_CONTEXT_REQUIRED","Token has no merchant context",403);try{return UUID.fromString(v);}catch(IllegalArgumentException e){throw new SettlementAccessException("SETTLEMENT_MERCHANT_CONTEXT_INVALID","Token merchant context is invalid",403);}}
}
