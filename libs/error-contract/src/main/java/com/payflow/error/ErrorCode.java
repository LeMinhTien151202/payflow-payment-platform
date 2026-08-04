package com.payflow.error;

/**
 * Một mã lỗi ổn định xuất hiện trong trường {@code code} của body Problem Details.
 *
 * <p>Interface này tồn tại để {@link ProblemDetails} có thể chấp nhận các mã lỗi mà nó chưa biết trước.
 * {@link PayFlowErrorCode} chứa các mã lỗi chung cấp platform; một mã lỗi nghiệp vụ như
 * {@code PAYMENT_DUPLICATE_REFERENCE} thuộc về service sở hữu invariant đó, và module map
 * cấm việc tập trung các mã đó ở đây. Nếu không có lớp trừu tượng này, một service sẽ phải lựa chọn giữa việc
 * thêm mã lỗi của nó vào enum dùng chung này hoặc không dùng builder chung nào cả.
 *
 * <p>Các lớp triển khai thường là enum, các tên hằng số của nó đồng thời đóng vai trò là giá trị truyền tải (wire values). Việc đổi tên là
 * một breaking change đối với API, vì phía client sẽ rẽ nhánh (branch) dựa trên nó.
 */
public interface ErrorCode {

    /** Giá trị dạng wire được đặt trong trường {@code code}. Ổn định; là một phần của hợp đồng public API. */
    String code();
}
