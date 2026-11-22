package hcmute.edu.vn.backend.service;

import org.springframework.http.HttpStatus;

/** Lỗi nghiệp vụ của lời mời, mang theo mã HTTP phù hợp. Là RuntimeException nên giao dịch tự rollback. */
public class InvitationException extends RuntimeException {

    private final HttpStatus status;

    public InvitationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
