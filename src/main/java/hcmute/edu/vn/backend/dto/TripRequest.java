package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class TripRequest {
    private String title;
    private String destination;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer memberCount;
    private BigDecimal totalBudget;
    private Boolean isPublic;
    private String visibility; // PRIVATE, FRIENDS, PUBLIC
    private List<String> companionEmails; // Danh sách email bạn đồng hành gửi từ React
}