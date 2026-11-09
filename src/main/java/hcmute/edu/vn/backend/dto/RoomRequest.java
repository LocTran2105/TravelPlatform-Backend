package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class RoomRequest {
    private String roomName;
    private String description;
    private BigDecimal pricePerNight;
    private String currency;
    private Integer capacity;
    private Integer totalUnits;
}
