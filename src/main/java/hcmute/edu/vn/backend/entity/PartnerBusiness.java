package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Entity trung tâm cho mọi cơ sở kinh doanh do Partner quản lý.
 * Bao gồm khách sạn, homestay, quán cà phê, nhà hàng,
 * khu tham quan và các loại hình dịch vụ khác.
 */
@Entity
@Table(name = "partner_businesses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PartnerBusiness {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "partner_id", nullable = false)
    private PartnerProfile partner;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "slug", length = 255, unique = true)
    private String slug;

    @Column(name = "business_type", nullable = false, length = 40)
    private String businessType = "OTHER";

    @Column(name = "short_description", length = 500)
    private String shortDescription;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "address", nullable = false, length = 500)
    private String address;

    @Column(name = "ward", length = 120)
    private String ward;

    @Column(name = "district", length = 120)
    private String district;

    @Column(name = "city", nullable = false, length = 120)
    private String city;

    @Column(name = "province", length = 120)
    private String province;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode = "VN";

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "phone_number", length = 30)
    private String phoneNumber;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "website_url", length = 1000)
    private String websiteUrl;

    @Column(name = "facebook_url", length = 1000)
    private String facebookUrl;

    @Column(name = "instagram_url", length = 1000)
    private String instagramUrl;

    @Column(name = "price_level")
    private Integer priceLevel;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "VND";

    @Column(name = "verification_status", nullable = false, length = 30)
    private String verificationStatus = "PENDING_REVIEW";

    @Column(name = "publication_status", nullable = false, length = 20)
    private String publicationStatus = "DRAFT";

    @Column(name = "operating_status", nullable = false, length = 30)
    private String operatingStatus = "OPEN";

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_by")
    private User verifiedBy;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    @Column(name = "source_note", length = 500)
    private String sourceNote;

    @JsonIgnore
    @OneToMany(mappedBy = "business", fetch = FetchType.LAZY)
    private List<Room> rooms;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    public void prePersist() {
        if (businessType == null || businessType.isBlank()) {
            businessType = "OTHER";
        }
        if (countryCode == null || countryCode.isBlank()) {
            countryCode = "VN";
        }
        if (currency == null || currency.isBlank()) {
            currency = "VND";
        }
        if (verificationStatus == null || verificationStatus.isBlank()) {
            verificationStatus = "PENDING_REVIEW";
        }
        if (publicationStatus == null || publicationStatus.isBlank()) {
            publicationStatus = "DRAFT";
        }
        if (operatingStatus == null || operatingStatus.isBlank()) {
            operatingStatus = "OPEN";
        }
    }
}
