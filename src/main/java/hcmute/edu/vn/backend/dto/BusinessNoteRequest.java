package hcmute.edu.vn.backend.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
@Getter
@Setter
public class BusinessNoteRequest {
    @NotBlank private String title;
    @NotBlank private String content;
    private String noteType = "TRAVEL_TIP";
    private Boolean active = true;
}