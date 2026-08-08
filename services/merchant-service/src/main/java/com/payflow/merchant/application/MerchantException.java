package com.payflow.merchant.application;

public final class MerchantException extends RuntimeException {
    private final String code;
    private final int status;
    public MerchantException(String code, String message, int status) { super(message); this.code=code; this.status=status; }
    public String code() { return code; }
    public int status() { return status; }
}
