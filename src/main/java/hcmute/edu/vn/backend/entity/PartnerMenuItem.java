package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "partner_menu_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PartnerMenuItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private PartnerMenuCategory category;
    @Column(name = "name", nullable = false, length = 200)
    private String name;
    @Column(name = "description", columnDefinition = "TEXT")
    private String description;
    @Column(name = "price", nullable = false, precision = 15, scale = 2)
    private BigDecimal price;
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "VND";
    @Column(name = "is_available", nullable = false)
    private Boolean available = true;
    @Column(name = "sort_order")
    private Integer sortOrder = 0;
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}