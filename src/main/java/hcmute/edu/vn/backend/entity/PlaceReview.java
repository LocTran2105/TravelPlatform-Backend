package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "place_reviews")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PlaceReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnoreProperties({"passwordHash", "hibernateLazyInitializer", "handler"})
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "location_name", nullable = false)
    private String locationName;

    private Integer rating;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    // Kiểm duyệt: PUBLISHED (mặc định) hoặc HIDDEN (bị kiểm duyệt viên ẩn).
    // Cột status của bảng đã có sẵn (PENDING, PUBLISHED, HIDDEN, REJECTED).
    @Column(name = "status", nullable = false, length = 20)
    private String status = "PUBLISHED";

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "moderated_by")
    private User moderatedBy;

    @Column(name = "moderation_note", length = 500)
    private String moderationNote;

    @Column(name = "moderated_at")
    private LocalDateTime moderatedAt;
}