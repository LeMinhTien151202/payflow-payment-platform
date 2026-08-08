package com.payflow.merchant.application;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public final class SecretMaterial {
    private final SecureRandom random = new SecureRandom();
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    public String generate(String prefix) {
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        return prefix+"_"+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public String hash(String plaintext) { return encoder.encode(plaintext); }
}
