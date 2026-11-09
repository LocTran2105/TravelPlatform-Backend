package hcmute.edu.vn.backend.dto;
import jakarta.validation.constraints.NotNull; import lombok.Getter; import lombok.Setter;
import java.time.LocalDate; import java.time.LocalTime;
@Getter @Setter
public class SpecialHoursRequest {
    @NotNull private LocalDate specialDate;
    private LocalTime openTime;
    private LocalTime closeTime;
    private Boolean closed = false;
    private String note;
}