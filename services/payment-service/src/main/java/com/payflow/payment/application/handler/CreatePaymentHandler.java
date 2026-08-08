package com.payflow.payment.application.handler;

import com.payflow.events.PayFlowTopics;
import com.payflow.events.payment.PaymentCreatedData;
import com.payflow.events.payment.PaymentEvents;
import com.payflow.payment.application.CreatePaymentResult;
import com.payflow.payment.application.PaymentAcceptance;
import com.payflow.payment.application.command.CreatePaymentCommand;
import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.exception.IdempotencyConflictException;
import com.payflow.payment.application.exception.MerchantNotRegisteredException;
import com.payflow.payment.application.idempotency.IdempotencyScope;
import com.payflow.payment.application.idempotency.IdempotentResponse;
import com.payflow.payment.application.idempotency.RequestFingerprint;
import com.payflow.payment.application.port.IdGenerator;
import com.payflow.payment.application.port.IdempotencyStore;
import com.payflow.payment.application.port.MerchantCatalog;
import com.payflow.payment.application.port.OutboxAppender;
import com.payflow.payment.application.port.PaymentRepository;
import com.payflow.payment.application.port.PaymentSagaStore;
import com.payflow.payment.application.saga.SagaRecoverySettings;
import com.payflow.payment.domain.model.MerchantSnapshot;
import com.payflow.payment.domain.model.Money;
import com.payflow.payment.domain.model.Payment;
import com.payflow.payment.domain.model.PaymentIntake;
import com.payflow.payment.domain.model.PaymentSaga;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chấp nhận một payment: chính xác 1 lần cho mỗi idempotency key, cùng với event của nó, trong 1 transaction.
 *
 * <h2>TẠI SAO CLASS NÀY KHÔNG DÙNG ANNOTATION {@code @Transactional}</h2>
 *
 * <p>Có 2 lý do, và cả hai đều thuộc dạng tạo ra một hệ thống bị hỏng ngầm thay vì làm bài test thất bại.
 *
 * <p>Thứ nhất là việc khôi phục từ một race condition bị thua đòi hỏi phải đọc dòng chiến thắng, và một transaction
 * vừa vi phạm unique constraint sẽ bị đánh dấu rollback-only — mọi câu lệnh tiếp theo trên đó đều thất bại.
 * Thao tác đọc phải xảy ra bên ngoài transaction bị lỗi, có nghĩa là ranh giới transaction phải nằm
 * bên trong method này chứ không bao quanh nó.
 *
 * <p>Thứ hai là một method có {@code @Transactional} khi gọi một method khác trong cùng class sẽ đi
 * qua {@code this}, chứ không qua proxy, do đó ranh giới bên trong sẽ âm thầm không tồn tại. Một
 * {@link TransactionTemplate} giúp ranh giới trở thành một câu lệnh rõ ràng thay vì là một thuộc tính phụ thuộc vào cách gọi
 * method.
 *
 * <h2>Thứ tự ghi bên trong transaction</h2>
 *
 * <p>Bản ghi idempotency được insert trước, trước khi insert payment. Hai request đồng thời mang
 * cùng key thông thường cũng mang cùng {@code merchantReference}, do đó cả 2 unique index đều tham gia; request
 * chạy trước sẽ quyết định exception nào caller nhận được. Việc insert dòng idempotency trước sẽ giúp
 * race condition được serialise trên {@code uq_idempotency_records_scope_key}, với câu trả lời là "replay response của request thắng".
 * Nếu payment được insert trước, cùng race condition đó sẽ xuất hiện dưới dạng duplicate merchant reference —
 * một lỗi 409 cho một client không làm gì sai.
 */
@Service
public class CreatePaymentHandler {

    /**
     * Thời gian một response được lưu giữ có thể replay.
     *
     * <p>Một ngày bao phủ bất kỳ retry policy hợp lý nào của client, bao gồm một queue bị sập qua đêm, và giới hạn
     * thời gian lưu giữ request body của merchant. Chưa có gì xóa các row hết hạn — job dọn dẹp nằm
     * ngoài Phase 1A — nên đây là một cam kết lưu giữ mà schema ghi nhận, chứ chưa phải cơ chế cưỡng chế.
     */
    static final Duration REPLAY_WINDOW = Duration.ofHours(24);

    private final MerchantCatalog merchants;
    private final PaymentRepository payments;
    private final PaymentSagaStore sagas;
    private final IdempotencyStore idempotency;
    private final OutboxAppender outbox;
    private final IdGenerator ids;
    private final Clock clock;
    private final SagaRecoverySettings sagaSettings;
    private final TransactionTemplate transactions;

    public CreatePaymentHandler(
            MerchantCatalog merchants,
            PaymentRepository payments,
            PaymentSagaStore sagas,
            IdempotencyStore idempotency,
            OutboxAppender outbox,
            IdGenerator ids,
            Clock clock,
            SagaRecoverySettings sagaSettings,
            TransactionTemplate transactions) {

        this.merchants = merchants;
        this.payments = payments;
        this.sagas = sagas;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.ids = ids;
        this.clock = clock;
        this.sagaSettings = sagaSettings;
        this.transactions = transactions;
    }

    /**
     * @throws MerchantNotRegisteredException nếu merchant của token không có trong catalog
     * @throws IdempotencyConflictException nếu key được sử dụng bởi một request khác
     * @throws com.payflow.payment.application.exception.DuplicateMerchantReferenceException nếu merchant
     *     đã sử dụng reference này rồi
     * @throws com.payflow.payment.domain.exception.PaymentDomainException nếu merchant không được phép giao dịch,
     *     không hỗ trợ thanh toán loại currency này, hoặc số tiền vượt quá hạn mức mỗi giao dịch
     */
    public CreatePaymentResult handle(CreatePaymentCommand command) {
        String scope = IdempotencyScope.createPayment(command.merchantId());
        String fingerprint = RequestFingerprint.of(command);

        // Đọc trước khi mở một transaction. Việc retry là trường hợp dự kiến đối với một client có queue đằng sau,
        // và việc trả lời retry không nên chiếm giữ write lock trên bất kỳ tài nguyên nào.
        var stored = idempotency.find(scope, command.idempotencyKey());
        if (stored.isPresent()) {
            return replay(stored.get(), fingerprint, scope, command.idempotencyKey());
        }

        // Phase 2 remote Merchant lookup happens before the local write transaction. A slow network
        // call must not occupy a PostgreSQL transaction/connection or extend lock lifetimes.
        MerchantSnapshot merchant = merchants
                .findById(command.merchantId())
                .orElseThrow(() -> new MerchantNotRegisteredException(command.merchantId()));

        try {
            return transactions.execute(status -> create(command, scope, fingerprint, merchant));
        } catch (ConcurrentIdempotentRequestException lostTheRace) {
            // Request kia đã committed tại thời điểm constraint báo cáo xung đột, nên response
            // của nó có thể đọc được ngay bây giờ. Nếu vì lý do nào đó không đọc được, key đã tồn tại mà không có response đằng sau
            // và không còn gì trung thực để trả về.
            return idempotency
                    .find(scope, command.idempotencyKey())
                    .map(winner -> replay(winner, fingerprint, scope, command.idempotencyKey()))
                    .orElseThrow(() -> lostTheRace);
        }
    }

    private CreatePaymentResult create(
            CreatePaymentCommand command,
            String scope,
            String fingerprint,
            MerchantSnapshot merchant) {

        Instant now = clock.instant();

        PaymentIntake intake =
                new PaymentIntake(
                        ids.newId(),
                        command.customerId(),
                        command.sourceAccountId(),
                        command.merchantReference(),
                        command.idempotencyKey(),
                        new Money(command.amount(), command.currency()),
                        command.description(),
                        command.metadata(),
                        now);

        Payment payment = Payment.create(merchant, intake);
        // Public 202 contract trả về snapshot CREATED ban đầu. Aggregate được lưu trữ chuyển tiến
        // sang RISK_CHECKING trong cùng 1 transaction đã append payment.created, nên kết quả Risk đến sau
        // không thể tới khi Payment vẫn khẳng định rằng nó chưa bao giờ được gửi đi.
        PaymentAcceptance acceptance = PaymentAcceptance.of(payment);
        payment.submitForRisk(now);

        idempotency.record(
                scope,
                command.idempotencyKey(),
                new IdempotentResponse(
                        fingerprint,
                        payment.id(),
                        CreatePaymentResult.Accepted.STATUS,
                        acceptance),
                now.plus(REPLAY_WINDOW));

        payments.save(payment);
        sagas.add(PaymentSaga.start(
                ids.newId(),
                payment.id(),
                now.plus(sagaSettings.stepTimeout()),
                now));

        outbox.append(
                PaymentEvents.PAYMENT_CREATED,
                PayFlowTopics.PAYMENT_EVENTS,
                payment.id().toString(),
                now,
                new PaymentCreatedData(
                        payment.id(),
                        payment.merchantId(),
                        payment.customerId(),
                        payment.sourceAccountId(),
                        payment.amount().amount(),
                        payment.amount().currency(),
                        payment.createdAt()));

        return new CreatePaymentResult.Accepted(acceptance);
    }

    private CreatePaymentResult replay(
            IdempotentResponse stored, String fingerprint, String scope, String idempotencyKey) {

        if (!stored.matches(fingerprint)) {
            throw new IdempotencyConflictException(scope, idempotencyKey);
        }
        return new CreatePaymentResult.Replayed(stored.body(), stored.responseStatus());
    }
}
