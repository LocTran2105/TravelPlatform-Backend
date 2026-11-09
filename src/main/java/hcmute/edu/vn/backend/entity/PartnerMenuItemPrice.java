package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "partner_menu_item_prices")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PartnerMenuItemPrice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_item_id", nullable = false)
    private PartnerMenuItem menuItem;
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;
    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "VND";
    @Column(name = "effective_from", nullable = false)
    private LocalDateTime effectiveFrom;
    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;
    @Column(name = "price_note", length = 500)
    private String priceNote;
    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}