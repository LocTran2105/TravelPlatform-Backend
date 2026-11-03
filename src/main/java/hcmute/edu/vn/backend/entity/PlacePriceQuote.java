package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "place_price_quotes")
@Getter
@Setter
public class PlacePriceQuote {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id", nullable = false)
    private Place place;

    @Column(name = "quote_type", nullable = false, length = 30)
    private String quoteType;

    @Column(name = "price_type", nullable = false, length = 20)
    private String priceType;

    @Column(nullable = false, length = 100)
    private String provider;

    @Column(name = "provider_offer_id")
    private String providerOfferId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "price_basis", length = 30)
    private String priceBasis;

    @Column(name = "check_in")
    private LocalDate checkIn;

    @Column(name = "check_out")
    private LocalDate checkOut;

    @Column(name = "guest_count")
    private Integer guestCount;

    @Column(name = "availability_status", length = 30)
    private String availabilityStatus;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "checked_at", nullable = false)
    private LocalDateTime checkedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}