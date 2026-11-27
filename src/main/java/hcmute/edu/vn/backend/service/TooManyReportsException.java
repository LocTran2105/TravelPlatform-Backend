package hcmute.edu.vn.backend.service;

/** Người dùng gửi báo cáo quá nhiều trong thời gian ngắn (HTTP 429). */
public class TooManyReportsException extends RuntimeException {
    public TooManyReportsException(String message) {
        super(message);
    }
}
