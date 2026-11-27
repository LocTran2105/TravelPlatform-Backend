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

/** Nhật ký thao tác kiểm duyệt: ai, làm gì, trên nội dung nào, vì sao. */
@Entity
@Table(name = "moderation_actions")
@Getter
@Setter
@NoArgsConstructor
public class ModerationAction {

    public static final String HIDE = "HIDE";
    public static final String RESTORE = "RESTORE";
    public static final String DISMISS = "DISMISS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;

    /** HIDE, RESTORE, DISMISS */
    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "target_type", nullable = false, length = 50)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "target_author_id")
    private Long targetAuthorId;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
