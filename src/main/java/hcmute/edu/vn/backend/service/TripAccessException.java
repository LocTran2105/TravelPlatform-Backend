package hcmute.edu.vn.backend.service;

import org.springframework.http.HttpStatus;

/** Không đủ quyền / không tìm thấy khi truy cập chuyến đi. Controller đổi thành mã HTTP tương ứng. */
public class TripAccessException extends RuntimeException {

    private final HttpStatus status;

    public TripAccessException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
