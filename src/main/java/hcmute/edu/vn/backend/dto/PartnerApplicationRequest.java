package hcmute.edu.vn.backend.dto;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter; import lombok.Setter;
@Getter @Setter
public class PartnerApplicationRequest {
    @NotBlank @Size(max = 255) private String displayName;
    @Size(max = 255) private String legalName;
    @Size(max = 50) private String taxCode;
    @Size(max = 150) private String contactPerson;
    @Email @Size(max = 255) private String contactEmail;
    @Size(max = 20) private String contactPhone;
    @Size(max = 500) private String businessAddress;
    private String description;
}