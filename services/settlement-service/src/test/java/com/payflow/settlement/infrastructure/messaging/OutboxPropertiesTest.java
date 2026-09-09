package com.payflow.settlement.infrastructure.messaging;
import static org.assertj.core.api.Assertions.*;
import java.time.Duration; import org.junit.jupiter.api.Test;
class OutboxPropertiesTest {
 @Test void leaseMustExceedDeliveryTimeout(){assertThatThrownBy(()->new OutboxProperties(true,Duration.ofMillis(500),10,Duration.ofSeconds(30),3,Duration.ofMinutes(1),Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);}
 @Test void rejectsNonPositiveDurations(){assertThatThrownBy(()->new OutboxProperties(true,Duration.ZERO,10,Duration.ofSeconds(31),3,Duration.ofMinutes(1),Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);}
}
