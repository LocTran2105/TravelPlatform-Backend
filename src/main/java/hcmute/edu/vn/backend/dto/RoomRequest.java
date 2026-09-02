package hcmute.edu.vn.backend.dto;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
public class RoomRequest {
    private String roomName;
    private BigDecimal pricePerNight;
    private Integer capacity;
}