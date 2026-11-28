package hcmute.edu.vn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TripContextDTO {
    private Long tripId;
    private String title;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer memberCount;
    private BigDecimal totalBudget;
    private List<TripDaySummaryDTO> tripDays;

    /**
     * Điểm neo để ưu tiên gợi ý ở gần: địa điểm ĐẦU TIÊN của lịch trình có tọa độ
     * (ngày nhỏ nhất, thứ tự nhỏ nhất). Null nếu lịch trình chưa có địa điểm nào trên bản đồ.
     */
    private String anchorName;
    private Double anchorLatitude;
    private Double anchorLongitude;
    /** Mô tả ngắn các nơi ở của chuyến đi cho prompt AI, vd. "Colline Dalat (28/11 → 30/11)". */
    private List<String> stays;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripDaySummaryDTO {
        private Long dayId;
        private Integer dayNumber;
        private LocalDate dayDate;
        private List<String> existingPlaces;
        /** Địa điểm có tọa độ của ngày, theo thứ tự lịch trình (dùng để gợi ý "gần X", "ngày N"). */
        private List<PlacePoint> placePoints;
        /** Nơi ở xuất phát của ngày (đêm trước; không có thì đêm nay): mốc khi gợi ý điểm cho ngày đó. */
        private PlacePoint stay;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlacePoint {
        private String name;
        private Double latitude;
        private Double longitude;
    }
}