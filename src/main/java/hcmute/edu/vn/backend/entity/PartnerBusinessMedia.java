package hcmute.edu.vn.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "partner_business_media")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PartnerBusinessMedia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "business_id", nullable = false)
    private PartnerBusiness business;

    @Column(name = "media_type", nullable = false, length = 20)
    private String mediaType = "IMAGE";

    @Column(name = "media_url", nullable = false, length = 1000)
    private String mediaUrl;

    @Column(name = "thumbnail_url", length = 1000)
    private String thumbnailUrl;

    @Column(name = "caption", length = 500)
    private String caption;

    @Column(name = "is_cover", nullable = false)
    private Boolean cover = false;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "moderation_status", nullable = false, length = 20)
    private String moderationStatus = "PENDING";

    @Column(name = "alt_text", length = 500)
    private String altText;

    @Column(name = "uploaded_by")
    private Long uploadedById;

    @Column(name = "reviewed_by")
    private Long reviewedById;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Backward-compatible constructor matching the entity fields before moderation metadata was mapped. */
    public PartnerBusinessMedia(Long id, PartnerBusiness business, String mediaType,
                                String mediaUrl, String thumbnailUrl, String caption,
                                Boolean cover, Integer sortOrder, LocalDateTime createdAt) {
        this.id = id;
        this.business = business;
        this.mediaType = mediaType;
        this.mediaUrl = mediaUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.caption = caption;
        this.cover = cover;
        this.sortOrder = sortOrder;
        this.createdAt = createdAt;
    }
}
