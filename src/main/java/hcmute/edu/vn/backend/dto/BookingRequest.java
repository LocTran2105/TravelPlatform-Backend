package hcmute.edu.vn.backend.dto;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;

@Getter
@Setter
public class BookingRequest {
    private Long roomId;
    private LocalDate checkIn;
    private LocalDate checkOut;
}