package hcmute.edu.vn.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Nơi ở của chuyến đi (khách sạn, homestay...), tách riêng khỏi các điểm đi chơi trong ngày.
 * Đêm D thuộc stay có checkInDate <= D < checkOutDate.
 */
@Entity
@Table(name = "trip_stays")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TripStay {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    /** Place trong DB (OSM/PARTNER) nếu người dùng chọn từ kết quả tìm kiếm; null nếu tự nhập. */
    @Column(name = "place_id")
    private Long placeId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "address", length = 500)
    private String address;

    @Column(name = "latitude", precision = 10, scale = 7)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 10, scale = 7)
    private BigDecimal longitude;

    @Column(name = "check_in_date", nullable = false)
    private LocalDate checkInDate;

    @Column(name = "check_out_date", nullable = false)
    private LocalDate checkOutDate;

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** true nếu người dùng ngủ ở đây vào đêm {@code night}. */
    public boolean coversNight(LocalDate night) {
        return night != null && checkInDate != null && checkOutDate != null
                && !night.isBefore(checkInDate) && night.isBefore(checkOutDate);
    }

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
