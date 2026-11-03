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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TripDaySummaryDTO {
        private Long dayId;
        private Integer dayNumber;
        private LocalDate dayDate;
        private List<String> existingPlaces;
    }
}