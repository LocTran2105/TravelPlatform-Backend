package hcmute.edu.vn.backend.dto;
import jakarta.validation.constraints.DecimalMin; import jakarta.validation.constraints.NotBlank;
import lombok.Getter; import lombok.Setter; import java.math.BigDecimal;
@Getter @Setter
public class MenuItemRequest {
    private Long categoryId;
    @NotBlank private String name;
    private String description;
    @DecimalMin("0.0") private BigDecimal price;
    private String currency = "VND";
    private Boolean available = true;
    private Integer sortOrder;
    private String priceNote;
}