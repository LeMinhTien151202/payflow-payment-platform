package com.payflow.merchant.api;

import com.payflow.merchant.application.MerchantException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
class MerchantExceptionHandler {
 @ExceptionHandler(MerchantException.class)
 ResponseEntity<ProblemDetail> business(MerchantException ex,HttpServletRequest request){
  return problem(HttpStatus.valueOf(ex.status()),ex.code(),ex.getMessage(),request);
 }
 @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class})
 ResponseEntity<ProblemDetail> invalid(Exception ex,HttpServletRequest request){
  return problem(HttpStatus.BAD_REQUEST,"MERCHANT_REQUEST_INVALID","Merchant request is invalid",request);
 }
 private static ResponseEntity<ProblemDetail> problem(HttpStatus status,String code,String detail,HttpServletRequest request){
  String correlation=request.getHeader("X-Correlation-Id");
  if(correlation==null||correlation.isBlank())correlation=UUID.randomUUID().toString();
  ProblemDetail body=ProblemDetail.forStatusAndDetail(status,detail);
  body.setTitle(status.getReasonPhrase());
  body.setProperty("code",code);
  body.setProperty("correlationId",correlation);
  return ResponseEntity.status(status).body(body);
 }
}
