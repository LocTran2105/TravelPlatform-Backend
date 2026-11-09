package hcmute.edu.vn.backend.dto;
import jakarta.validation.constraints.NotBlank; import jakarta.validation.constraints.NotNull;
import lombok.Getter; import lombok.Setter; import java.math.BigDecimal; import java.time.LocalDateTime;
@Getter @Setter
public class PromotionRequest {
    @NotBlank private String title;
    private String description;
    @NotBlank private String promotionType;
    private BigDecimal discountValue;
    @NotNull private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private String terms;
}