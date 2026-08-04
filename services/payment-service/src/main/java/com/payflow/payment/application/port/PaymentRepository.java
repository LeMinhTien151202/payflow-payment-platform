package com.payflow.payment.application.port;

import com.payflow.payment.application.exception.ConcurrentIdempotentRequestException;
import com.payflow.payment.application.exception.DuplicateMerchantReferenceException;
import com.payflow.payment.domain.model.Payment;
import java.util.Optional;
import java.util.UUID;

/**
 * Lưu trữ payment aggregate.
 *
 * <p>Mỗi aggregate có 1 repository riêng, chứ không phải mỗi bảng có 1 repository: {@link #save} ghi payment và status
 * history mà aggregate đó ghi nhận, vì một chuyển đổi trạng thái (status change) không có dòng history tương ứng chính là trạng thái
 * mà bảng history tồn tại để ngăn chặn.
 *
 * <p>Hai ngoại lệ (exception) dưới đây được khai báo tại đây thay vì để dưới dạng {@code DataAccessException}. Cả hai
 * đều là các kết quả nghiệp vụ (business outcomes) tình cờ được phát hiện bởi 1 unique index, và caller phải phân biệt
 * chúng: một bên là sai sót phía client và bên còn lại là một race condition với câu trả lời đúng là replay. Việc phân định
 * bên nào với bên nào đòi hỏi phải dựa vào tên của constraint, điều mà chỉ có adapter mới có thể quan sát được.
 */
public interface PaymentRepository {

    /**
     * Insert payment cùng với tất cả mọi thay đổi trạng thái (status change) mà nó ghi nhận.
     *
     * @throws DuplicateMerchantReferenceException nếu merchant đã sử dụng reference này rồi
     * @throws ConcurrentIdempotentRequestException nếu một request đồng thời mang cùng idempotency
     *     key đã commit một payment trước
     */
    void save(Payment payment);

    /**
     * Tìm một payment thuộc sở hữu của một merchant.
     *
     * <p>Merchant là một tham số bắt buộc, không phải là thứ mà caller sẽ filter sau đó. AGENTS.md phần 8
     * yêu cầu việc kiểm tra ownership phải thực hiện ở biên giới application boundary, và một method signature không thể được gọi
     * nếu không chỉ định merchant là một phiên bản quy tắc mà người review không thể bỏ quên. Một method
     * {@code findById(paymentId)} nằm cạnh nó cuối cùng sẽ được gọi bởi ai đó không
     * nhận ra việc kiểm tra đó là trách nhiệm của họ.
     *
     * @return empty trong cả hai trường hợp: khi không có payment đó và khi nó thuộc về merchant khác — caller
     *     tuyệt đối không được phân biệt hai trường hợp này, nếu không endpoint sẽ trở thành một cách để dò tìm các payment id
     */
    Optional<Payment> find(UUID paymentId, UUID merchantId);
}
