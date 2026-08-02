package com.payflow.accountledger.infrastructure.persistence;

import com.payflow.accountledger.ledger.application.port.RefundJournalRecord;
import com.payflow.accountledger.ledger.application.port.RefundJournalStore;
import com.payflow.accountledger.ledger.domain.model.Journal;
import com.payflow.events.refund.RefundRequestedData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
class JdbcRefundJournalStore implements RefundJournalStore {

    private static final String FIND = """
            select refund_id, payment_id, merchant_id, source_account_id,
                   journal_id, amount, currency
            from ledger.refund_postings
            where refund_id = :refundId
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
            insert into ledger.refund_postings (
                refund_id, payment_id, merchant_id, source_account_id,
                journal_id, amount, currency)
            values (
                :refundId, :paymentId, :merchantId, :sourceAccountId,
                :journalId, :amount, :currency)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcRefundJournalStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<RefundJournalRecord> findByRefundId(UUID refundId) {
        var rows = jdbc.query(
                FIND,
                new MapSqlParameterSource("refundId", refundId),
                JdbcRefundJournalStore::map);
        return rows.stream().findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void save(Journal journal, RefundRequestedData request) {
        jdbc.update(INSERT_JOURNAL, new MapSqlParameterSource()
                .addValue("id", journal.id())
                .addValue("referenceType", journal.referenceType())
                .addValue("referenceId", journal.referenceId())
                .addValue("journalType", journal.journalType())
                .addValue("description", journal.description())
                .addValue("currency", journal.currency())
                .addValue("status", journal.status().name())
                .addValue("occurredAt", journal.occurredAt().atOffset(ZoneOffset.UTC))
                .addValue("createdAt", journal.createdAt().atOffset(ZoneOffset.UTC)));
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
                .addValue("refundId", request.refundId())
                .addValue("paymentId", request.paymentId())
                .addValue("merchantId", request.merchantId())
                .addValue("sourceAccountId", request.accountId())
                .addValue("journalId", journal.id())
                .addValue("amount", request.amount())
                .addValue("currency", request.currency()));
    }

    private static RefundJournalRecord map(ResultSet result, int row) throws SQLException {
        return new RefundJournalRecord(
                result.getObject("refund_id", UUID.class),
                result.getObject("payment_id", UUID.class),
                result.getObject("merchant_id", UUID.class),
                result.getObject("source_account_id", UUID.class),
                result.getObject("journal_id", UUID.class),
                result.getBigDecimal("amount"),
                result.getString("currency"));
    }
}
