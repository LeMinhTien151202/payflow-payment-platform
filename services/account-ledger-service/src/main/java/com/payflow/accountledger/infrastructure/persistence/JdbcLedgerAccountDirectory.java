package com.payflow.accountledger.infrastructure.persistence;

import com.payflow.accountledger.ledger.application.port.LedgerAccountDirectory;
import com.payflow.accountledger.ledger.application.port.LedgerAccountPair;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcLedgerAccountDirectory implements LedgerAccountDirectory {

    private static final String FIND = """
            select owner_type, id
            from ledger.ledger_accounts
            where currency = :currency
              and ((owner_type = 'MERCHANT' and owner_id = :merchantId)
                or (owner_type = 'CUSTOMER_ACCOUNT' and owner_id = :sourceAccountId))
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcLedgerAccountDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<LedgerAccountPair> findPaymentAccounts(
            UUID customerId, UUID merchantId, String currency) {
        return findPair(merchantId, customerId, currency);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<LedgerAccountPair> findRefundAccounts(
            UUID merchantId, UUID sourceAccountId, String currency) {
        return findPair(merchantId, sourceAccountId, currency);
    }

    private Optional<LedgerAccountPair> findPair(
            UUID merchantId, UUID customerOwnerId, String currency) {
        var parameters = new MapSqlParameterSource()
                .addValue("merchantId", merchantId)
                .addValue("sourceAccountId", customerOwnerId)
                .addValue("currency", currency);
        var ids = new HashMap<String, UUID>();
        jdbc.query(FIND, parameters, (RowCallbackHandler) result ->
                ids.put(result.getString("owner_type"), result.getObject("id", UUID.class)));
        UUID merchant = ids.get("MERCHANT");
        UUID customer = ids.get("CUSTOMER_ACCOUNT");
        return merchant == null || customer == null
                ? Optional.empty()
                : Optional.of(new LedgerAccountPair(merchant, customer));
    }
}
