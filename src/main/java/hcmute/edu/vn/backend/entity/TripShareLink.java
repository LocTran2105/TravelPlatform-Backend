package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** Link chia sẻ chuyến đi theo vai trò (EDITOR / VIEWER). Mỗi chuyến đi tối đa một link cho mỗi vai trò. */
@Entity
@Table(name = "trip_share_links")
@Getter
@Setter
@NoArgsConstructor
public class TripShareLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(nullable = false, length = 10)
    private String role; // EDITOR, VIEWER

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
