package hcmute.edu.vn.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "trip_days")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TripDay {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id", nullable = false)
    private Trip trip;

    @Column(name = "day_number", nullable = false)
    private Integer dayNumber;

    @Column(name = "day_date")
    private LocalDate dayDate;

    @OneToMany(mappedBy = "tripDay", cascade = CascadeType.ALL)
    private List<TripDestination> destinations;

}