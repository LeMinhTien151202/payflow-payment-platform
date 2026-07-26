package com.payflow.payment.api.response;

/** Stable success envelope from spec section 10.2. */
public record ApiResponse<T>(T data, ResponseMeta meta) {
}
