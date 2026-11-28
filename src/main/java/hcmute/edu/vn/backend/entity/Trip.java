package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "trips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Trip {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnoreProperties({"passwordHash", "hibernateLazyInitializer", "handler"})
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(name = "destination")
    private String destination; // Điểm đến chính (VD: Đà Lạt, Phú Quốc)

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "member_count")
    private Integer memberCount = 1;

    @Column(name = "total_budget")
    private BigDecimal totalBudget = BigDecimal.ZERO;

    @Column(name = "is_public")
    private Boolean isPublic = false;

    @Column(name = "visibility")
    private String visibility = "PRIVATE"; // PRIVATE, FRIENDS, PUBLIC

    @Column(name = "cloned_from_trip_id")
    private Long clonedFromTripId;

    // Bản sao được tạo từ lịch trình trong bài viết nào (đếm lượt sao chép theo bài).
    @Column(name = "cloned_from_post_id")
    private Long clonedFromPostId;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayNumber ASC")
    private List<TripDay> tripDays;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<TripMember> members;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "public_status")
    private String publicStatus = "DRAFT"; // DRAFT, PENDING, APPROVED, REJECTED

    @Column(name = "average_rating")
    private Double averageRating = 0.0;

    @Column(name = "total_reviews")
    private Integer totalReviews = 0;

    // Ảnh bìa riêng của chuyến đi (URL Cloudinary: từ thư viện hoặc người dùng tự tải lên). NULL = chưa chọn.
    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    // Ảnh gợi ý theo điểm đến, chỉ để trả về cho frontend (không lưu DB).
    @Transient
    private String suggestedCoverUrl;

    // Thêm đoạn này vào bên trong class Trip.java
    @OneToOne(mappedBy = "trip", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private TripPreference preference;
}