package com.payflow.accountledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** PostgreSQL evidence for local-only fixtures and their non-replenishing restart semantics. */
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LocalSeedPersistenceIT extends AbstractPostgresIT {

    private static final UUID HAPPY_ACCOUNT =
            UUID.fromString("039bedb6-b2d6-47df-aa25-2035e39136a3");
    private static final UUID LOW_BALANCE_ACCOUNT =
            UUID.fromString("55555555-5555-4555-8555-555555555555");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;

    @Test
    void loadsCompleteFakeMappingsAndNeverReplenishesAnExistingBalance() {
        assertThat(balance(HAPPY_ACCOUNT)).isEqualByComparingTo("1000000.0000");
        assertThat(balance(LOW_BALANCE_ACCOUNT)).isEqualByComparingTo("100000.0000");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ledger.ledger_accounts"
                                + " WHERE owner_type = 'MERCHANT'"
                                + " AND owner_id = '11111111-1111-4111-8111-111111111111'",
                        Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM ledger.ledger_accounts"
                                + " WHERE owner_type = 'CUSTOMER_ACCOUNT'"
                                + " AND owner_id IN ("
                                + " '3beff442-7f10-4504-aab4-12d985cf3e95',"
                                + " '039bedb6-b2d6-47df-aa25-2035e39136a3',"
                                + " '44444444-4444-4444-8444-444444444444',"
                                + " '55555555-5555-4555-8555-555555555555')",
                        Integer.class))
                .isEqualTo(4);

        jdbc.update(
                "UPDATE account.accounts SET available_balance = 900000.0000 WHERE id = ?",
                HAPPY_ACCOUNT);
        flyway.migrate();

        assertThat(balance(HAPPY_ACCOUNT)).isEqualByComparingTo("900000.0000");
    }

    private BigDecimal balance(UUID accountId) {
        return jdbc.queryForObject(
                "SELECT available_balance FROM account.accounts WHERE id = ?",
                BigDecimal.class,
                accountId);
    }
}
