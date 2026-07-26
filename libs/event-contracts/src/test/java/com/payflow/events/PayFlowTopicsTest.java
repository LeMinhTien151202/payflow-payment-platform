package com.payflow.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks the topic names against accidental change.
 *
 * <p>A renamed topic is not a refactor: producers write to the new name, already-deployed consumers
 * keep polling the old one, and nothing fails — messages simply stop arriving. This test is the only
 * thing standing between a tidy-up commit and that outcome.
 */
class PayFlowTopicsTest {

    @Test
    @DisplayName("topic names are exactly the eight fixed by spec 8.1")
    void namesMatchSpec() {
        assertThat(PayFlowTopics.PAYMENT_EVENTS).isEqualTo("payflow.payment.events.v1");
        assertThat(PayFlowTopics.ACCOUNT_EVENTS).isEqualTo("payflow.account.events.v1");
        assertThat(PayFlowTopics.LEDGER_EVENTS).isEqualTo("payflow.ledger.events.v1");
        assertThat(PayFlowTopics.RISK_EVENTS).isEqualTo("payflow.risk.events.v1");
        assertThat(PayFlowTopics.REFUND_EVENTS).isEqualTo("payflow.refund.events.v1");
        assertThat(PayFlowTopics.NOTIFICATION_COMMANDS).isEqualTo("payflow.notification.commands.v1");
        assertThat(PayFlowTopics.SETTLEMENT_EVENTS).isEqualTo("payflow.settlement.events.v1");
        assertThat(PayFlowTopics.DEAD_LETTER).isEqualTo("payflow.dead-letter.v1");
    }

    @Test
    @DisplayName("no topic name is duplicated, so two contexts cannot silently share a topic")
    void namesAreDistinct() {
        assertThat(declaredTopicNames()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every declared topic is versioned, so a breaking change has an escape hatch")
    void everyTopicIsVersioned() {
        // payflow.<context>[.<kind>].v<n> — the dead-letter topic has no kind segment.
        assertThat(declaredTopicNames())
                .allMatch(name -> name.matches("^payflow\\.[a-z-]+(\\.[a-z]+)?\\.v\\d+$"));
    }

    private static List<String> declaredTopicNames() {
        List<String> names = new ArrayList<>();
        for (Field field : PayFlowTopics.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    names.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("topic constant must be accessible: " + field.getName(), e);
                }
            }
        }
        return names;
    }
}
