package hcmute.edu.vn.backend.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@Builder
public class PlaceRecommendationDTO {
    private Long placeId;
    private String name;
    private String address;
    private String placeType;
    private BigDecimal rating;
    private Integer userRatingsTotal;
    private List<String> categories;
    private BigDecimal estimatedCostMin;
    private BigDecimal estimatedCostMax;

    // Thuộc tính quan trọng nhất do hệ thống chấm điểm
    private Double matchScore;
    private String matchReason; // Vd: "Khớp 80% sở thích của bạn"
}