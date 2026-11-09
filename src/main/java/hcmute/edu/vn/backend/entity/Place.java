package hcmute.edu.vn.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
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

    // UPDATE: schema mới mặc định nguồn discovery là OSM, không phải GOOGLE.
    @Column(name = "provider", nullable = false, length = 30)
    @Builder.Default
    private String provider = "OSM";

    @Column(name = "provider_place_id", length = 255)
    private String providerPlaceId;

    // NEW: liên kết tùy chọn tới dữ liệu cơ sở kinh doanh đã được xác minh.
    // Dùng scalar ID ở giai đoạn này để không phụ thuộc entity Hotel tạm thời.
    @Column(name = "partner_business_id")
    private Long partnerBusinessId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "destination", nullable = false, length = 100)
    private String destination;

    @Column(name = "place_type", nullable = false, length = 40)
    @Builder.Default
    private String placeType = "ATTRACTION";

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "rating", precision = 3, scale = 2)
    private BigDecimal rating;

    @Column(name = "user_ratings_total")
    private Integer userRatingsTotal;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "categories", columnDefinition = "json")
    private List<String> categories;

    @Column(name = "estimated_cost_min", precision = 15, scale = 2)
    private BigDecimal estimatedCostMin;

    @Column(name = "estimated_cost_max", precision = 15, scale = 2)
    private BigDecimal estimatedCostMax;

    @Column(name = "currency", nullable = false, length = 3)
    @Builder.Default
    private String currency = "VND";

    @Column(name = "price_updated_at")
    private LocalDateTime priceUpdatedAt;

    @Column(name = "fetched_at")
    private LocalDateTime fetchedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @jakarta.persistence.PrePersist
    public void prePersist() {
        // fetchedAt giữ NULL nếu đây là dữ liệu do đối tác nhập, không phải dữ liệu discovery.
        if (provider == null) provider = "OSM";
        if (placeType == null) placeType = "ATTRACTION";
        if (currency == null) currency = "VND";
    }
}
