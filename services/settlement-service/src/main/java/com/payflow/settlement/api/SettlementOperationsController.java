package com.payflow.settlement.api;
import com.payflow.observability.CorrelationId; import com.payflow.settlement.application.*;
import io.swagger.v3.oas.annotations.Operation; import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate; import java.util.*;
import org.springframework.http.HttpStatus; import org.springframework.security.core.annotation.AuthenticationPrincipal; import org.springframework.security.oauth2.jwt.Jwt; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/operations") @Tag(name="Settlement operations",description="Audited operator actions for batch calculation, completion and reconciliation")
class SettlementOperationsController {
 private final SettlementOperationsService operations; private final SettlementQueryService queries;
 SettlementOperationsController(SettlementOperationsService o,SettlementQueryService q){operations=o;queries=q;}
 @PostMapping("/settlements/run") @ResponseStatus(HttpStatus.ACCEPTED) @Operation(summary="Calculate daily settlement",description="Locks each OPEN batch, verifies item totals, and transitions it to READY or FAILED. Replaying the command is safe.")
 List<SettlementBatchView> calculate(@AuthenticationPrincipal Jwt jwt,@RequestParam LocalDate date,@RequestHeader(name=CorrelationId.HEADER,required=false)String correlation){return operations.calculate(date,jwt.getSubject(),CorrelationId.resolveOrGenerate(correlation));}
 @PostMapping("/settlements/{id}/complete") @Operation(summary="Complete a READY settlement",description="Atomically marks a READY batch COMPLETED, writes an audit record and appends settlement.completed to the transactional outbox.")
 SettlementBatchView complete(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestHeader(name=CorrelationId.HEADER,required=false)String correlation){return operations.complete(id,jwt.getSubject(),CorrelationId.resolveOrGenerate(correlation));}
 @PostMapping("/reconciliation/run") @ResponseStatus(HttpStatus.ACCEPTED) @Operation(summary="Run reconciliation",description="Compares durable payment/refund/account/ledger facts with settlement items and creates auditable issues; it never silently changes money.")
 ReconciliationRunResult reconcile(@AuthenticationPrincipal Jwt jwt,@RequestParam LocalDate date,@RequestHeader(name=CorrelationId.HEADER,required=false)String correlation){return operations.reconcile(date,jwt.getSubject(),CorrelationId.resolveOrGenerate(correlation));}
 @GetMapping("/reconciliation/issues") @Operation(summary="List reconciliation issues",description="Returns bounded OPEN or RESOLVED discrepancies for an operator-selected business date.")
 PageResult<ReconciliationIssueView> issues(@RequestParam LocalDate date,@RequestParam(defaultValue="OPEN")String status,@RequestParam(defaultValue="0")int page,@RequestParam(defaultValue="20")int size){return queries.issues(date,status,page,size);}
}
