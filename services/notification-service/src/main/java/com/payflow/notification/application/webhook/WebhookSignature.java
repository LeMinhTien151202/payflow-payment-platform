package com.payflow.notification.application.webhook;

import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class WebhookSignature {
    private WebhookSignature(){}
    public static String sign(String secret,long timestamp,String rawBody){
        try{
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            byte[] value=mac.doFinal((timestamp+"."+rawBody).getBytes(StandardCharsets.UTF_8));
            return "v1="+java.util.HexFormat.of().formatHex(value);
        }catch(java.security.GeneralSecurityException e){throw new IllegalStateException("HMAC is unavailable",e);}
    }
    public static boolean verify(String secret,long timestamp,String rawBody,String signature){
        return java.security.MessageDigest.isEqual(
          sign(secret,timestamp,rawBody).getBytes(StandardCharsets.US_ASCII),
          signature.getBytes(StandardCharsets.US_ASCII));
    }
}
