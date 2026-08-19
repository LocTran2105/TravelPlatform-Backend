package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "shares")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Share {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "target_type", nullable = false)
    private String targetType; // TRIP, HOTEL, CUSTOM_PLACE

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    private String platform; // FACEBOOK, TWITTER, COPY_LINK

    @Column(name = "shared_at", insertable = false, updatable = false)
    private LocalDateTime sharedAt;
}
