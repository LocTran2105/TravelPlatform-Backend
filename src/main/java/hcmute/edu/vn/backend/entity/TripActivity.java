package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Một dòng trong lịch sử thao tác của chuyến đi. created_at lưu theo giờ UTC. */
@Entity
@Table(name = "trip_activities")
@Getter
@Setter
@NoArgsConstructor
public class TripActivity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    /** Null nếu tài khoản người thực hiện đã bị xóa. */
    @Column(name = "actor_id")
    private Long actorId;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(name = "target_type", length = 20)
    private String targetType;

    @Column(name = "target_id")
    private Long targetId;

    @Column(length = 255)
    private String label;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
