package hcmute.edu.vn.backend.dto;
import jakarta.validation.constraints.NotBlank; import lombok.Getter; import lombok.Setter;
@Getter @Setter
public class MenuCategoryRequest {
    @NotBlank private String name;
    private String description;
    private Integer sortOrder;
    private Boolean active = true;
}