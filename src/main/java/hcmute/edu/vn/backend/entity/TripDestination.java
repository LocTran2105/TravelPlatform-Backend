package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalTime;

@Entity
@Table(name = "trip_destinations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripDestination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_day_id", nullable = false)
    private TripDay tripDay;

    // ==========================================
    // CÁC TRƯỜNG MỚI BỔ SUNG CHO AI PLANNER
    // ==========================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "place_id")
    private Place place;

    @Builder.Default
    @Column(name = "item_type", nullable = false, length = 30)
    private String itemType = "ATTRACTION"; // ATTRACTION, ACCOMMODATION, RESTAURANT, TRANSPORT...

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "end_time")
    private LocalTime endTime;

    // ==========================================
    // CÁC TRƯỜNG CŨ GIỮ NGUYÊN (BACKWARD COMPATIBILITY)
    // ==========================================

    @Column(name = "place_name", nullable = false)
    private String placeName;

    private Double latitude;

    private Double longitude;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "estimated_cost")
    private BigDecimal estimatedCost;

    @Column(name = "order_index")
    private Integer orderIndex;

    @Builder.Default
    @Column(name = "is_visited")
    private Boolean isVisited = false;

    @Column(name = "time_range")
    private String timeRange;

    @Column(name = "cost")
    private String cost;
}