package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import hcmute.edu.vn.backend.dto.TripSnapshot;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "posts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Không trả thông tin nhạy cảm của User ra JSON.
    @JsonIgnoreProperties({
            "passwordHash",
            "resetPasswordToken",
            "tokenExpiryDate",
            "hibernateLazyInitializer",
            "handler"
    })
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    // Chuyến đi NGUỒN của lịch trình trong bài (để tác giả cập nhật bản chụp). NULL khi bài không
    // gắn lịch trình HOẶC chuyến gốc đã bị xóa (bản chụp bên dưới vẫn còn).
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id")
    private Trip trip;

    // Bản chụp lịch trình + chi phí lúc đăng / lúc tác giả bấm cập nhật. Bài hiển thị và cho sao chép
    // từ bản chụp này, nên tác giả sửa chuyến đi gốc thì bài không đổi. NULL = bài không có lịch trình.
    @JsonIgnore
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "trip_snapshot")
    private TripSnapshot tripSnapshot;

    // Thời điểm chụp (giờ UTC, cùng quy ước với trip_activities).
    @JsonIgnore
    @Column(name = "trip_snapshot_at")
    private LocalDateTime tripSnapshotAt;

    // SHA-256 nội dung bản chụp: so với chuyến gốc hiện tại để biết "lịch trình gốc đã thay đổi".
    @JsonIgnore
    @Column(name = "trip_snapshot_hash", length = 64)
    private String tripSnapshotHash;

    @Column(columnDefinition = "TEXT")
    private String caption;

    @Column(name = "location_name", length = 255)
    private String locationName;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    private Integer rating;

    // Trạng thái kiểm duyệt bài đăng.
    // PENDING, APPROVED, REJECTED, HIDDEN.
    @Column(
            name = "moderation_status",
            nullable = false,
            length = 20
    )
    private String moderationStatus = "PENDING";

    // Admin thực hiện kiểm duyệt.
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moderated_by")
    private User moderatedBy;

    @Column(name = "moderation_note", columnDefinition = "TEXT")
    private String moderationNote;

    @Column(name = "moderated_at")
    private LocalDateTime moderatedAt;

    @Column(
            name = "created_at",
            insertable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    @Column(name = "visibility", nullable = false, length = 20)
    private String visibility = "PUBLIC"; // PUBLIC, FRIENDS, FOLLOWERS, PRIVATE

    @Column(name = "status", nullable = false, length = 20)
    private String status = "PUBLISHED"; // PUBLISHED, DELETED

    @PrePersist
    public void prePersist() {
        // SỬA: Cho bài đăng được duyệt (APPROVED) ngay lập tức khi tạo
        if (moderationStatus == null || moderationStatus.isBlank() || moderationStatus.equals("PENDING")) {
            moderationStatus = "APPROVED";
        }
        if (visibility == null || visibility.isBlank()) {
            visibility = "PUBLIC";
        }
        if (status == null || status.isBlank()) {
            status = "PUBLISHED";
        }
    }
}