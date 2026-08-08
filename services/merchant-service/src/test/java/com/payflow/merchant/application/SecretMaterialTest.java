package com.payflow.merchant.application;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SecretMaterialTest {
 @Test void plaintextIsOneTimeMaterialAndOnlyHashMatches(){
  SecretMaterial material=new SecretMaterial(); String key=material.generate("pfk"); String hash=material.hash(key);
  assertThat(key).startsWith("pfk_"); assertThat(hash).doesNotContain(key).startsWith("$2");
 }
}
