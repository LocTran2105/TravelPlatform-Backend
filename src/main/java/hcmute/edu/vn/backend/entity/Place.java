package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "places")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Place {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String provider = "GOOGLE";

    @Column(name = "provider_place_id", length = 255)
    private String providerPlaceId;

    @Column(nullable = false)
    private String name;

    @Column(length = 500)
    private String address;

    @Column(nullable = false, length = 100)
    private String destination;

    @Column(name = "place_type", nullable = false, length = 30)
    @Builder.Default
    private String placeType = "ATTRACTION";

    private Double latitude;

    private Double longitude;

    @Column(precision = 3, scale = 2)
    private BigDecimal rating;

    @Column(name = "user_ratings_total")
    private Integer userRatingsTotal;

    // SỬA LỖI: Chỉ định rõ List<String> thay vì List thô
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "json")
    private List<String> categories;

    @Column(name = "estimated_cost_min", precision = 15, scale = 2)
    private BigDecimal estimatedCostMin;

    @Column(name = "estimated_cost_max", precision = 15, scale = 2)
    private BigDecimal estimatedCostMax;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "VND";

    @Column(name = "price_updated_at")
    private LocalDateTime priceUpdatedAt;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    @PrePersist
    public void prePersist() {
        if (fetchedAt == null) fetchedAt = LocalDateTime.now();
        if (provider == null) provider = "GOOGLE";
        if (placeType == null) placeType = "ATTRACTION";
        if (currency == null) currency = "VND";
    }
}