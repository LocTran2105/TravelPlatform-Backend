package hcmute.edu.vn.backend.entity;


import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "trips")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Trip {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private String title;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "member_count")
    private Integer memberCount;

    @Column(name = "total_budget")
    private BigDecimal totalBudget;

    @Column(name = "is_public")
    private Boolean isPublic = false;

    @Column(name = "cloned_from_trip_id")
    private Long clonedFromTripId;

    @OneToMany(mappedBy = "trip", cascade = CascadeType.ALL)
    private List<TripDay> tripDays;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "public_status")
    private String publicStatus = "DRAFT"; // DRAFT, PENDING, APPROVED, REJECTED
}