package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "images")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Image {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false)
    private String entityType; // Sẽ nhận các giá trị: TRIP, POST, HOTEL, CUSTOM_PLACE

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "image_url", nullable = false)
    private String imageUrl; // Chứa đường link trả về từ Cloudinary

    @Column(name = "media_type")
    private String mediaType = "IMAGE"; // Phân biệt IMAGE hoặc VIDEO để Frontend render thẻ <img> hay <video>

    private String caption;

    @Column(name = "is_cover")
    private Boolean isCover = false;

    @Column(name = "uploaded_at", insertable = false, updatable = false)
    private LocalDateTime uploadedAt;
}