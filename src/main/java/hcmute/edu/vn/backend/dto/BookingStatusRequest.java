package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BookingStatusRequest {
    private String status; // CONFIRMED, REJECTED, CANCELLED
}
