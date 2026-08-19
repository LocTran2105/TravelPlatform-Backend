package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class TripRequest {
    private String title;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer memberCount;
    private BigDecimal totalBudget;
    private Boolean isPublic;
}