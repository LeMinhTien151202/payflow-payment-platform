package com.payflow.settlement.application;
import static org.assertj.core.api.Assertions.*;
import com.payflow.settlement.application.outbox.OutboxPublishPolicy;
import java.time.Duration; import org.junit.jupiter.api.Test;
class OutboxPublishPolicyTest {
 @Test void boundsExponentialBackoff(){var p=new OutboxPublishPolicy(10,Duration.ofMinutes(2),10,Duration.ofMinutes(5));assertThat(p.backoffFor(1)).isEqualTo(Duration.ofSeconds(2));assertThat(p.backoffFor(30)).isEqualTo(Duration.ofMinutes(5));}
}
