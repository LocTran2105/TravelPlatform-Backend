package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalTime;

@Getter
@Setter
public class AddDestinationRequest {
    private Long tripDayId;
    private Long placeId;
    private Integer orderIndex;
    private LocalTime startTime;
    private LocalTime endTime;
    private String note;
}