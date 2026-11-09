package hcmute.edu.vn.backend.dto;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
@Getter
@Setter
public class BusinessMediaRequest {
    private String mediaType = "IMAGE";
    @NotBlank private String mediaUrl;
    private String thumbnailUrl;
    private String caption;
    private Boolean cover = false;
    private Integer sortOrder;
}