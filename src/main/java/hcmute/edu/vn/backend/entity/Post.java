package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.persistence.*;
import lombok.*;
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

    // Bỏ qua các trường nhạy cảm hoặc vòng lặp của User khi trả về JSON
    @JsonIgnoreProperties({"passwordHash", "hibernateLazyInitializer", "handler"})
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(columnDefinition = "TEXT")
    private String caption;

    @Column(name = "location_name")
    private String locationName; // Tên địa điểm (VD: Chợ Bến Thành)

    private Double latitude;
    private Double longitude;

    private Integer rating; // Số sao từ 1-5

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}