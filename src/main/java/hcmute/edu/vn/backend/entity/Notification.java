package hcmute.edu.vn.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Thông báo gửi tới một người dùng.
 *
 * <p>Dùng id thuần (recipientId, actorId) thay vì quan hệ JPA để việc tạo thông báo không phải nạp
 * thêm thực thể và không bị lỗi lazy-loading. Thông tin người thực hiện (tên, avatar) được nạp
 * lúc đọc để luôn đúng với dữ liệu hiện tại.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    // Mạng xã hội
    public static final String FOLLOW = "FOLLOW";
    public static final String POST_LIKE = "POST_LIKE";
    public static final String POST_COMMENT = "POST_COMMENT";
    public static final String COMMENT_REPLY = "COMMENT_REPLY";
    // Kiểm duyệt
    public static final String CONTENT_HIDDEN = "CONTENT_HIDDEN";
    public static final String CONTENT_RESTORED = "CONTENT_RESTORED";
    public static final String REPORT_REVIEWED = "REPORT_REVIEWED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    /** Người gây ra thông báo (null với thông báo từ hệ thống / kiểm duyệt). */
    @Column(name = "actor_id")
    private Long actorId;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(name = "target_type", length = 30)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    /** Đường dẫn giao diện sẽ mở khi bấm vào thông báo (có thể null). */
    @Column(length = 255)
    private String link;

    /** Lý do kiểm duyệt (CONTENT_HIDDEN / CONTENT_RESTORED), null với loại khác. */
    @Column(length = 500)
    private String message;

    /** Số người đã thực hiện (gộp thông báo thích). */
    @Column(name = "actor_count", nullable = false)
    private int actorCount = 1;

    @Column(name = "is_read", nullable = false)
    private boolean seen = false;

    /** Khóa chống trùng / gộp, duy nhất theo từng người nhận. Null = không chống trùng. */
    @Column(name = "dedupe_key", length = 120)
    private String dedupeKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** Thời điểm xảy ra sự kiện mới nhất; dùng để sắp xếp (đánh dấu đã đọc không làm đổi thứ tự). */
    @Column(name = "event_at", nullable = false)
    private LocalDateTime eventAt = LocalDateTime.now();
}
