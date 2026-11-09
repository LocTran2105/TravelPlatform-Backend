package hcmute.edu.vn.backend.dto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalTime;
@Getter
@Setter
public class BusinessHoursRequest {
    @Min(1) @Max(7) private Integer dayOfWeek;
    private LocalTime openTime;
    private LocalTime closeTime;
    private Boolean closed = false;
}