package com.payflow.risk.infrastructure.redis;

import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.risk.application.port.RiskSignalProvider;
import com.payflow.risk.application.port.RiskSignalSnapshot;
import com.payflow.risk.infrastructure.config.RiskVelocityProperties;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Atomically records one candidate payment and returns customer velocity windows.
 * Payment IDs are Redis members, so a retry cannot count the same payment twice.
 */
@Component
class RedisRiskSignalProvider implements RiskSignalProvider {

    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final DefaultRedisScript<List> SCRIPT = new DefaultRedisScript<>("""
            local function add_decimal_strings(left, right)
              local carry = 0
              local result = ''
              local i = string.len(left)
              local j = string.len(right)
              while i > 0 or j > 0 or carry > 0 do
                local a = 0
                local b = 0
                if i > 0 then a = string.byte(left, i) - 48 end
                if j > 0 then b = string.byte(right, j) - 48 end
                local sum = a + b + carry
                result = tostring(sum % 10) .. result
                carry = math.floor(sum / 10)
                i = i - 1
                j = j - 1
              end
              return result
            end

            local existing = redis.call('HGET', KEYS[2], ARGV[1])
            if existing and existing ~= ARGV[3] then
              return redis.error_reply('payment amount changed for existing risk velocity member')
            end
            redis.call('ZADD', KEYS[1], 'NX', ARGV[2], ARGV[1])
            redis.call('HSETNX', KEYS[2], ARGV[1], ARGV[3])

            local stale = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[5])
            for _, member in ipairs(stale) do redis.call('HDEL', KEYS[2], member) end
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', '(' .. ARGV[5])

            local count = redis.call('ZCOUNT', KEYS[1], ARGV[4], ARGV[2])
            local total = '0'
            local current = redis.call('ZRANGEBYSCORE', KEYS[1], ARGV[5], ARGV[2])
            for _, member in ipairs(current) do
              local amount = redis.call('HGET', KEYS[2], member)
              if amount then total = add_decimal_strings(total, amount) end
            end
            redis.call('EXPIRE', KEYS[1], ARGV[6])
            redis.call('EXPIRE', KEYS[2], ARGV[6])
            return { tostring(count), total }
            """, List.class);

    private final StringRedisTemplate redis;
    private final RiskVelocityProperties properties;

    RedisRiskSignalProvider(StringRedisTemplate redis, RiskVelocityProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public RiskSignalSnapshot collect(PaymentCreatedData payment) {
        long occurredAt = payment.createdAt().toEpochMilli();
        String customerTag = "{" + payment.customerId() + "}";
        List<?> result = redis.execute(
                SCRIPT,
                List.of(
                        "risk:customer:" + customerTag + ":payments",
                        "risk:customer:" + customerTag + ":amounts"),
                payment.paymentId().toString(),
                Long.toString(occurredAt),
                toMinorUnits(payment.amount()),
                Long.toString(occurredAt - ONE_MINUTE.toMillis()),
                Long.toString(occurredAt - ONE_HOUR.toMillis()),
                Long.toString(properties.retention().toSeconds()));
        if (result == null || result.size() != 2) {
            throw new IllegalStateException("Redis risk velocity script returned an invalid result");
        }
        int count = Integer.parseInt(result.get(0).toString());
        BigDecimal total = new BigDecimal(new BigInteger(result.get(1).toString()), 4);

        // payment.created v1 has no trusted device/IP/failure/merchant-risk signals. Neutral values
        // are explicit until a versioned enrichment contract is introduced.
        return new RiskSignalSnapshot(count, total, false, 0, false, false);
    }

    private static String toMinorUnits(BigDecimal amount) {
        return amount.movePointRight(4).toBigIntegerExact().toString();
    }
}
