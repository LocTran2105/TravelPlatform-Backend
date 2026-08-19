package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "trip_destinations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TripDestination {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_day_id", nullable = false)
    private TripDay tripDay;

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
}