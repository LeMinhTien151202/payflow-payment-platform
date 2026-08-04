package com.payflow.error;

/**
 * Các mã lỗi cấp platform là một phần của hợp đồng public API.
 *
 * <p>Các mã lỗi này mang tính ổn định: phía client rẽ nhánh xử lý dựa trên chúng, nên việc đổi tên một mã là breaking change.
 * Mã trạng thái HTTP đứng một mình không cấu thành hợp đồng — mã {@code 409} có thể đại diện cho nhiều ý nghĩa khác nhau — đó là
 * lý do vì sao mỗi response lỗi đều mang theo một mã code.
 *
 * <p>Chỉ các mối quan tâm chung cấp platform mới thuộc về đây. Các mã lỗi nghiệp vụ như
 * {@code ACCOUNT_INSUFFICIENT_FUNDS} do service sở hữu invariant tương ứng quản lý và không được
 * tập trung hóa vào shared library này. Các mã đó triển khai {@link ErrorCode} ngay tại service của chính chúng.
 */
public enum PayFlowErrorCode implements ErrorCode {

    /** Xác thực bị thiếu, không đúng định dạng hoặc đã hết hạn. Ánh xạ sang 401. */
    AUTH_UNAUTHENTICATED("AUTH_UNAUTHENTICATED"),

    /** Đã xác thực nhưng thiếu scope, role hoặc quyền sở hữu tài nguyên cần thiết. Ánh xạ sang 403. */
    AUTH_FORBIDDEN("AUTH_FORBIDDEN"),

    /** Request thất bại tại bước validation ranh giới. Ánh xạ sang 400. */
    REQUEST_VALIDATION_FAILED("REQUEST_VALIDATION_FAILED"),

    /** Tài nguyên được yêu cầu không tồn tại, hoặc không hiển thị đối với caller này. Ánh xạ sang 404. */
    RESOURCE_NOT_FOUND("RESOURCE_NOT_FOUND"),

    /** Lỗi phía server không lường trước được. Ánh xạ sang 500 và không bao giờ rò rỉ chi tiết nội bộ. */
    INTERNAL_ERROR("INTERNAL_ERROR");

    private final String code;

    PayFlowErrorCode(String code) {
        this.code = code;
    }

    /** Giá trị wire được đặt trong trường {@code code} của body Problem Details. */
    @Override
    public String code() {
        return code;
    }
}
