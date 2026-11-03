package hcmute.edu.vn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaceDiscoveryDTO {
    private Long placeId;
    private String provider;         // "OSM", "INTERNAL"
    private String providerPlaceId;  // Ví dụ: "node/123456"
    private String name;
    private String address;
    private String destination;
    private String category;         // TOURIST_ATTRACTION, RESTAURANT, CAFE, ACCOMMODATION
    private List<String> categories;
    private Double latitude;
    private Double longitude;
    private BigDecimal rating;       // Null nếu không có dữ liệu thật
    private Integer userRatingsTotal;
    private BigDecimal estimatedPrice; // Null nếu không có giá thật
    private String priceType;        // UNKNOWN, REFERENCE, ESTIMATE, LIVE_QUOTE
    private Double distanceMeters;
    private Double matchScore;
    private String matchReason;
    private String sourceUrl;
    private String attribution;      // "© OpenStreetMap contributors"
}