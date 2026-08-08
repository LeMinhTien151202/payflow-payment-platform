package com.payflow.merchant.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class SecretCipher {
    private final SecretKeySpec key;
    private final SecureRandom random=new SecureRandom();
    public SecretCipher(@Value("${payflow.merchant.encryption-key-base64}") String encoded) {
        byte[] raw=Base64.getDecoder().decode(encoded);
        if (raw.length!=32) throw new IllegalArgumentException("Merchant encryption key must decode to 32 bytes");
        key=new SecretKeySpec(raw,"AES");
    }
    public String encrypt(String value) { return crypt(Cipher.ENCRYPT_MODE,value); }
    public String decrypt(String encoded) {
        try {
            byte[] all=Base64.getDecoder().decode(encoded);
            byte[] nonce=java.util.Arrays.copyOfRange(all,0,12);
            byte[] ciphertext=java.util.Arrays.copyOfRange(all,12,all.length);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,nonce));
            return new String(cipher.doFinal(ciphertext),StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Unable to decrypt merchant secret",e); }
    }
    private String crypt(int mode,String value) {
        try {
            byte[] nonce=new byte[12]; random.nextBytes(nonce);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,key,new GCMParameterSpec(128,nonce));
            byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] all=new byte[nonce.length+encrypted.length];
            System.arraycopy(nonce,0,all,0,nonce.length); System.arraycopy(encrypted,0,all,nonce.length,encrypted.length);
            return Base64.getEncoder().encodeToString(all);
        } catch (GeneralSecurityException e) { throw new IllegalStateException("Unable to encrypt merchant secret",e); }
    }
}
