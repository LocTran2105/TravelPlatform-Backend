package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.Map;

@Getter
@Setter
public class TrackingEventRequest {
    private Long userId; // Trong thực tế sẽ lấy từ Token (SecurityContext)
    private Long tripId;
    private Long placeId;
    private String eventType; // VIEW, ADD_TO_TRIP, DISMISS
    private BigDecimal recommendationScore;
    private Integer position;
    private Map<String, Object> context;
}