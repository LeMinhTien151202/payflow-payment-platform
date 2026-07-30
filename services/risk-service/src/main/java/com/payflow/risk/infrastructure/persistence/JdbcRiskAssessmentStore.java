package com.payflow.risk.infrastructure.persistence;

import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.risk.application.port.RiskAssessmentRecord;
import com.payflow.risk.application.port.RiskAssessmentStore;
import com.payflow.risk.application.port.RiskSignalSnapshot;
import com.payflow.risk.domain.model.RiskAssessment;
import com.payflow.risk.domain.model.RiskDecision;
import com.payflow.risk.domain.model.RiskLevel;
import com.payflow.risk.domain.model.RiskRuleCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
class JdbcRiskAssessmentStore implements RiskAssessmentStore {

    private static final TypeReference<List<String>> RULES = new TypeReference<>() {};
    private static final String SELECT = """
            select id, payment_id, customer_id, merchant_id, source_account_id, amount, currency,
                   payment_created_at, payment_count_last_minute, total_amount_last_hour,
                   new_device, failed_payments_last_10m, merchant_suspicious, ip_country_changed,
                   score, level, decision, matched_rules::text as matched_rules,
                   policy_version, assessed_at
              from risk.risk_assessments where payment_id = :paymentId
            """;
    private static final String INSERT = """
            insert into risk.risk_assessments (
                id, payment_id, customer_id, merchant_id, source_account_id, amount, currency,
                payment_created_at, payment_count_last_minute, total_amount_last_hour,
                new_device, failed_payments_last_10m, merchant_suspicious, ip_country_changed,
                score, level, decision, matched_rules, policy_version, assessed_at)
            values (
                :id, :paymentId, :customerId, :merchantId, :sourceAccountId, :amount, :currency,
                :paymentCreatedAt, :count1m, :total1h, :newDevice, :failed10m,
                :merchantSuspicious, :ipChanged, :score, :level, :decision,
                cast(:matchedRules as jsonb), :policyVersion, :assessedAt)
            on conflict (payment_id) do nothing
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    JdbcRiskAssessmentStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<RiskAssessmentRecord> findByPaymentId(UUID paymentId) {
        return jdbc.query(SELECT, new MapSqlParameterSource("paymentId", paymentId), this::map)
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean saveIfAbsent(RiskAssessmentRecord record) {
        var payment = record.payment();
        var assessment = record.assessment();
        var parameters = new MapSqlParameterSource()
                .addValue("id", record.id())
                .addValue("paymentId", payment.paymentId())
                .addValue("customerId", payment.customerId())
                .addValue("merchantId", payment.merchantId())
                .addValue("sourceAccountId", payment.sourceAccountId())
                .addValue("amount", payment.amount())
                .addValue("currency", payment.currency())
                .addValue("paymentCreatedAt", payment.createdAt())
                .addValue("count1m", record.signals().paymentCountLastMinute())
                .addValue("total1h", record.signals().totalAmountLastHour())
                .addValue("newDevice", record.signals().newDevice())
                .addValue("failed10m", record.signals().failedPaymentsLastTenMinutes())
                .addValue("merchantSuspicious", record.signals().merchantSuspicious())
                .addValue("ipChanged", record.signals().ipCountryChanged())
                .addValue("score", assessment.score())
                .addValue("level", assessment.level().name())
                .addValue("decision", assessment.decision().name())
                .addValue("matchedRules", objectMapper.writeValueAsString(
                        assessment.matchedRules().stream().map(Enum::name).toList()))
                .addValue("policyVersion", assessment.policyVersion())
                .addValue("assessedAt", record.assessedAt());
        return jdbc.update(INSERT, parameters) == 1;
    }

    private RiskAssessmentRecord map(ResultSet row, int rowNumber) throws SQLException {
        UUID paymentId = row.getObject("payment_id", UUID.class);
        UUID customerId = row.getObject("customer_id", UUID.class);
        UUID merchantId = row.getObject("merchant_id", UUID.class);
        var payment = new PaymentCreatedData(
                paymentId,
                merchantId,
                customerId,
                row.getObject("source_account_id", UUID.class),
                row.getBigDecimal("amount"),
                row.getString("currency"),
                row.getObject("payment_created_at", OffsetDateTime.class).toInstant());
        var snapshot = new RiskSignalSnapshot(
                row.getInt("payment_count_last_minute"),
                row.getBigDecimal("total_amount_last_hour"),
                row.getBoolean("new_device"),
                row.getInt("failed_payments_last_10m"),
                row.getBoolean("merchant_suspicious"),
                row.getBoolean("ip_country_changed"));
        List<RiskRuleCode> matched = objectMapper
                .readValue(row.getString("matched_rules"), RULES)
                .stream()
                .map(RiskRuleCode::valueOf)
                .toList();
        var assessment = new RiskAssessment(
                paymentId,
                customerId,
                merchantId,
                row.getString("policy_version"),
                row.getInt("score"),
                RiskLevel.valueOf(row.getString("level")),
                RiskDecision.valueOf(row.getString("decision")),
                matched);
        return new RiskAssessmentRecord(
                row.getObject("id", UUID.class),
                payment,
                snapshot,
                assessment,
                row.getObject("assessed_at", OffsetDateTime.class).toInstant());
    }
}
