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
    private String entityType; // TRIP, TRIP_DESTINATION, HOTEL, CUSTOM_PLACE, ALERT

    @Column(name = "entity_id", nullable = false)
    private Long entityId;

    @Column(name = "image_url", nullable = false)
    private String imageUrl;

    private String caption;

    @Column(name = "is_cover")
    private Boolean isCover = false;

    @Column(name = "uploaded_at", insertable = false, updatable = false)
    private LocalDateTime uploadedAt;
}