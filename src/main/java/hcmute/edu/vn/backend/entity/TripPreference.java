package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "trip_preferences")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripPreference {

    @Id
    @Column(name = "trip_id")
    private Long tripId;

    @JsonIgnore // Tránh lỗi vòng lặp đệ quy khi serialize JSON
    @OneToOne(fetch = FetchType.LAZY)
    @MapsId // Báo cho Hibernate biết dùng tripId làm khóa chính và khóa ngoại
    @JoinColumn(name = "trip_id")
    private Trip trip;

    // Hibernate 6 sẽ tự động parse List<String> thành JSON array khi lưu vào MySQL
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preference_tags")
    private List<String> preferenceTags;

    @Column(name = "preferred_accommodation", length = 30)
    private String preferredAccommodation; // VD: HOTEL, HOMESTAY

    @Column(name = "preferred_transport", length = 30)
    private String preferredTransport; // VD: MOTORBIKE, CAR

    @Builder.Default
    @Column(length = 30)
    private String intensity = "BALANCED"; // VD: RELAX, BALANCED, PACKED

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}