package com.payflow.settlement.api;
final class SettlementAccessException extends RuntimeException { final String code; final int status; SettlementAccessException(String c,String m,int s){super(m);code=c;status=s;} }
