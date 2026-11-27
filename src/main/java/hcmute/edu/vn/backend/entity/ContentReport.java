package hcmute.edu.vn.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Báo cáo vi phạm của người dùng (bảng reports). */
@Entity
@Table(name = "reports")
@Getter
@Setter
@NoArgsConstructor
public class ContentReport {

    public static final String PENDING = "PENDING";
    public static final String RESOLVED = "RESOLVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    /** POST, POST_COMMENT, PLACE_REVIEW */
    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** SPAM, HARASSMENT, SENSITIVE, MISINFORMATION, OTHER */
    @Column(name = "reason_code", length = 30)
    private String reasonCode;

    /** Mô tả thêm của người báo cáo (không bắt buộc, tối đa 500 ký tự). */
    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    /** PENDING, RESOLVED, REJECTED */
    @Column(name = "status", nullable = false, length = 20)
    private String status = PENDING;

    /** HIDDEN (đã ẩn nội dung) hoặc DISMISSED (bỏ qua). */
    @Column(name = "resolution", length = 20)
    private String resolution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null || status.isBlank()) {
            status = PENDING;
        }
    }
}
