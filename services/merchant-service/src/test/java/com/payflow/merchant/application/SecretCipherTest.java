package com.payflow.merchant.application;

import static org.assertj.core.api.Assertions.*;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretCipherTest {
 @Test void encryptsWithRandomNonceAndDecryptsWithoutPlaintextPersistence(){
  String key=Base64.getEncoder().encodeToString(new byte[32]);
  SecretCipher cipher=new SecretCipher(key);
  String one=cipher.encrypt("whsec_example"); String two=cipher.encrypt("whsec_example");
  assertThat(one).isNotEqualTo(two).doesNotContain("whsec_example");
  assertThat(cipher.decrypt(one)).isEqualTo("whsec_example");
 }
 @Test void rejectsWrongKeyLength(){
  assertThatThrownBy(()->new SecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
   .isInstanceOf(IllegalArgumentException.class);
 }
}
