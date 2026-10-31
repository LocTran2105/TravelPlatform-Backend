package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Một ảnh/video đính kèm trong đánh giá địa điểm (file nằm trên Cloudinary, DB chỉ lưu URL). */
@Entity
@Table(name = "place_review_media")
@Getter @Setter @NoArgsConstructor
public class PlaceReviewMedia {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "review_id", nullable = false)
    private PlaceReview review;

    @Column(name = "media_url", nullable = false, length = 1000)
    private String url;

    /** IMAGE hoặc VIDEO */
    @Column(name = "media_type", nullable = false, length = 10)
    private String type;
}