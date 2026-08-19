package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "likes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserLike {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "target_type", nullable = false)
    private String targetType; // TRIP, TRIP_COMMENT, REVIEW, HOTEL, CUSTOM_PLACE

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}