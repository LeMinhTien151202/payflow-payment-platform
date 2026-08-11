package com.payflow.settlement.api;
import com.payflow.observability.CorrelationId;
import com.payflow.settlement.application.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.springframework.http.*; import org.springframework.web.bind.annotation.*;
@RestControllerAdvice
class SettlementExceptionHandler {
 @ExceptionHandler(SettlementAccessException.class) ResponseEntity<ProblemDetail> access(SettlementAccessException e,HttpServletRequest r){return problem(e.status,e.code,e.getMessage(),r);}
 @ExceptionHandler(SettlementNotFoundException.class) ResponseEntity<ProblemDetail> missing(SettlementNotFoundException e,HttpServletRequest r){return problem(404,"SETTLEMENT_NOT_FOUND",e.getMessage(),r);}
 @ExceptionHandler(SettlementStateException.class) ResponseEntity<ProblemDetail> state(SettlementStateException e,HttpServletRequest r){return problem(409,"SETTLEMENT_INVALID_STATE",e.getMessage(),r);}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ProblemDetail> invalid(IllegalArgumentException e,HttpServletRequest r){return problem(400,"SETTLEMENT_REQUEST_INVALID",e.getMessage(),r);}
 private static ResponseEntity<ProblemDetail> problem(int status,String code,String detail,HttpServletRequest request){var httpStatus=HttpStatus.valueOf(status);var p=ProblemDetail.forStatusAndDetail(httpStatus,detail);p.setTitle(httpStatus.getReasonPhrase());p.setType(URI.create("https://payflow.local/problems/"+code.toLowerCase().replace('_','-')));p.setProperty("code",code);p.setProperty("correlationId",CorrelationId.resolveOrGenerate(request.getHeader(CorrelationId.HEADER)));return ResponseEntity.status(status).body(p);}
}
