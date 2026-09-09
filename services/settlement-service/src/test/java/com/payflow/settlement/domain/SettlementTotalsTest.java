package com.payflow.settlement.domain;
import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal; import java.time.Instant; import java.util.*;
import org.junit.jupiter.api.Test;
class SettlementTotalsTest {
 private static final UUID PAYMENT=UUID.randomUUID(), MERCHANT=UUID.randomUUID(); private static final Instant AT=Instant.parse("2026-08-08T01:00:00Z");
 @Test void calculatesGrossRefundFeeAndNetWithoutLosingScale(){var payment=SettlementContribution.payment(PAYMENT,MERCHANT,new BigDecimal("100000.0000"),new BigDecimal("2000.0000"),"VND",AT);var refund=SettlementContribution.refund(UUID.randomUUID(),PAYMENT,MERCHANT,new BigDecimal("25000.0000"),new BigDecimal("500.0000"),"VND",AT);var totals=SettlementTotals.calculate(List.of(payment,refund));assertThat(totals.grossAmount()).isEqualByComparingTo("100000");assertThat(totals.refundAmount()).isEqualByComparingTo("25000");assertThat(totals.feeAmount()).isEqualByComparingTo("1500");assertThat(totals.netAmount()).isEqualByComparingTo("73500");assertThat(totals.transactionCount()).isOne();assertThat(totals.refundCount()).isOne();}
 @Test void rejectsFeeGreaterThanPayment(){assertThatThrownBy(()->SettlementContribution.payment(PAYMENT,MERCHANT,new BigDecimal("10"),new BigDecimal("11"),"VND",AT)).isInstanceOf(IllegalArgumentException.class);}
 @Test void rejectsInconsistentNet(){assertThatThrownBy(()->new SettlementTotals(new BigDecimal("10"),BigDecimal.ZERO,BigDecimal.ONE,new BigDecimal("10"),1,0)).isInstanceOf(IllegalArgumentException.class);}
}
