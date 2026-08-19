package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Report {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", nullable = false)
    private User reporter;

    @Column(name = "target_type", nullable = false)
    private String targetType; // TRIP, COMMENT, ALERT, CUSTOM_PLACE

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    private String status = "PENDING"; // PENDING, RESOLVED, REJECTED

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}