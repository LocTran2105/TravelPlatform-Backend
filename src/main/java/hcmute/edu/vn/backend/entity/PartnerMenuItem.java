package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Món/dịch vụ trong menu của cơ sở Partner. Ánh xạ đúng bảng partner_menu_items:
 * - business_id: bắt buộc trong DB; tự lấy từ category khi lưu nên service không cần set.
 * - giá hiện tại nằm ở cột current_price (field Java vẫn tên price).
 * - trạng thái còn/hết nằm ở cột availability_status (AVAILABLE/UNAVAILABLE/SEASONAL);
 *   field Java vẫn là Boolean available, chuyển đổi qua AvailabilityConverter.
 */
@Entity
@Table(name = "partner_menu_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PartnerMenuItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private PartnerBusiness business;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private PartnerMenuCategory category;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "current_price", precision = 15, scale = 2)
    private BigDecimal price;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "VND";

    @Convert(converter = AvailabilityConverter.class)
    @Column(name = "availability_status", nullable = false, length = 20)
    private Boolean available = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void fillDerivedColumns() {
        if (business == null && category != null) {
            business = category.getBusiness();
        }
        if (sortOrder == null) sortOrder = 0;
        if (available == null) available = true;
    }

    /** true <-> AVAILABLE, false <-> UNAVAILABLE; SEASONAL (dữ liệu có sẵn) được coi là còn phục vụ. */
    @Converter
    public static class AvailabilityConverter implements AttributeConverter<Boolean, String> {
        @Override
        public String convertToDatabaseColumn(Boolean available) {
            return Boolean.FALSE.equals(available) ? "UNAVAILABLE" : "AVAILABLE";
        }

        @Override
        public Boolean convertToEntityAttribute(String status) {
            return status == null || !"UNAVAILABLE".equalsIgnoreCase(status.trim());
        }
    }
}