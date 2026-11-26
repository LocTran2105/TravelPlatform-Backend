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

    /**
     * Độ tin cậy do BACKEND gắn (AI không được tự đặt):
     * VERIFIED     - Place PARTNER của Business đã được Admin xác minh, đang công khai. Kéo thả được.
     * MAP_DATA     - POI có thật trên OpenStreetMap, đã lưu trong DB và có tọa độ. Kéo thả được.
     * AI_REFERENCE - AI gợi ý nhưng chưa đối chiếu được trên bản đồ: không có placeId/tọa độ, chỉ để tham khảo.
     */
    private String trustLevel;
    private String trustLabel;
    /** true khi địa điểm do AI đề xuất tên rồi backend tìm thấy trên bản đồ. */
    private Boolean aiSuggested;
    /** Nguồn của rating: PROVIDER (dữ liệu nguồn) hoặc COMMUNITY (đánh giá của người dùng trong hệ thống). */
    private String ratingSource;
    /** Khoảng cách tới địa điểm đầu tiên của lịch trình (điểm neo) và tên điểm neo, để hiển thị "cách ... km". */
    private Double distanceFromAnchorMeters;
    private String anchorName;
    /** true nếu OSM có Wikipedia/Wikidata cho địa điểm này (tín hiệu "nổi tiếng"). */
    private Boolean notable;
}