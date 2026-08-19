package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "trip_reviews")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TripReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Byte rating; // Dùng Byte cho TINYINT (1-5)

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}