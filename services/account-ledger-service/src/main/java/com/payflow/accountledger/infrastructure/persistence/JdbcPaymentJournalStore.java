package com.payflow.accountledger.infrastructure.persistence;

import com.payflow.accountledger.ledger.application.port.PaymentJournalRecord;
import com.payflow.accountledger.ledger.application.port.PaymentJournalStore;
import com.payflow.accountledger.ledger.domain.model.Journal;
import com.payflow.events.ledger.LedgerPostPaymentRequestedData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcPaymentJournalStore implements PaymentJournalStore {

    private static final String FIND = """
            select payment_id, customer_id, merchant_id, journal_id, amount, currency
            from ledger.payment_postings
            where payment_id = :paymentId
            """;
    private static final String INSERT_JOURNAL = """
            insert into ledger.journals (
                id, reference_type, reference_id, journal_type, description,
                currency, status, occurred_at, created_at)
            values (
                :id, :referenceType, :referenceId, :journalType, :description,
                :currency, :status, :occurredAt, :createdAt)
            """;
    private static final String INSERT_ENTRY = """
            insert into ledger.entries (
                id, journal_id, ledger_account_id, direction, amount, currency)
            values (:id, :journalId, :ledgerAccountId, :direction, :amount, :currency)
            """;
    private static final String INSERT_POSTING = """
            insert into ledger.payment_postings (
                payment_id, customer_id, merchant_id, journal_id, amount, currency)
            values (:paymentId, :customerId, :merchantId, :journalId, :amount, :currency)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPaymentJournalStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<PaymentJournalRecord> findByPaymentId(UUID paymentId) {
        var rows = jdbc.query(
                FIND,
                new MapSqlParameterSource("paymentId", paymentId),
                JdbcPaymentJournalStore::map);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Journal journal, LedgerPostPaymentRequestedData request) {
        jdbc.update(INSERT_JOURNAL, new MapSqlParameterSource()
                .addValue("id", journal.id())
                .addValue("referenceType", journal.referenceType())
                .addValue("referenceId", journal.referenceId())
                .addValue("journalType", journal.journalType())
                .addValue("description", journal.description())
                .addValue("currency", journal.currency())
                .addValue("status", journal.status().name())
                .addValue("occurredAt", journal.occurredAt())
                .addValue("createdAt", journal.createdAt()));
        for (var entry : journal.entries()) {
            jdbc.update(INSERT_ENTRY, new MapSqlParameterSource()
                    .addValue("id", entry.id())
                    .addValue("journalId", journal.id())
                    .addValue("ledgerAccountId", entry.ledgerAccountId())
                    .addValue("direction", entry.direction().name())
                    .addValue("amount", entry.amount())
                    .addValue("currency", entry.currency()));
        }
        jdbc.update(INSERT_POSTING, new MapSqlParameterSource()
                .addValue("paymentId", request.paymentId())
                .addValue("customerId", request.customerId())
                .addValue("merchantId", request.merchantId())
                .addValue("journalId", journal.id())
                .addValue("amount", request.amount())
                .addValue("currency", request.currency()));
    }

    private static PaymentJournalRecord map(ResultSet result, int row) throws SQLException {
        return new PaymentJournalRecord(
                result.getObject("payment_id", UUID.class),
                result.getObject("customer_id", UUID.class),
                result.getObject("merchant_id", UUID.class),
                result.getObject("journal_id", UUID.class),
                result.getBigDecimal("amount"),
                result.getString("currency"));
    }
}
