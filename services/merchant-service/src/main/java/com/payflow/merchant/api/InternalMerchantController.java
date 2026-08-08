package com.payflow.merchant.api;

import com.payflow.merchant.application.MerchantApplicationService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/merchants")
class InternalMerchantController {
 private final MerchantApplicationService service;
 InternalMerchantController(MerchantApplicationService service){this.service=service;}
 @GetMapping("/{merchantId}/webhook")
 @PreAuthorize("hasAuthority('SCOPE_merchant:internal:read')")
 MerchantApplicationService.InternalWebhook webhook(@PathVariable UUID merchantId){return service.getWebhook(merchantId);}
 @GetMapping("/{merchantId}/payment-policy")
 @PreAuthorize("hasAuthority('SCOPE_merchant:internal:read')")
 com.payflow.merchant.application.port.MerchantStore.PaymentPolicy paymentPolicy(
   @PathVariable UUID merchantId){return service.getPaymentPolicy(merchantId);}
}
