package hcmute.edu.vn.backend.dto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalTime;

@Getter
@Setter
public class DestinationRequest {
    private String placeName;
    private Double latitude;
    private Double longitude;
    private String note;
    private BigDecimal estimatedCost;
    private Integer orderIndex;
    // Bổ sung vào class DestinationRequest
    private Long placeId;
    private String itemType;
    private LocalTime startTime;
    private LocalTime endTime;

    @JsonProperty("isVisited")
    private Boolean isVisited;

    @JsonProperty("timeRange")
    private String timeRange;

    @JsonProperty("cost")
    private String cost;
}