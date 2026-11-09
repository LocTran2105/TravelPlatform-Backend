package hcmute.edu.vn.backend.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter; import lombok.Setter;
import java.math.BigDecimal;
@Getter @Setter
public class PartnerBusinessRequest {
    @NotBlank @Size(max = 255) private String name;
    @NotBlank @Size(max = 40) private String businessType;
    @Size(max = 500) private String shortDescription;
    private String description;
    @NotBlank @Size(max = 500) private String address;
    @Size(max = 120) private String ward;
    @Size(max = 120) private String district;
    @NotBlank @Size(max = 120) private String city;
    @Size(max = 120) private String province;
    @Size(min = 2, max = 2) private String countryCode = "VN";
    private BigDecimal latitude;
    private BigDecimal longitude;
    @Size(max = 30) private String phoneNumber;
    @Size(max = 255) private String email;
    @Size(max = 1000) private String websiteUrl;
    @Size(max = 1000) private String facebookUrl;
    @Size(max = 1000) private String instagramUrl;
    private Integer priceLevel;
    @Size(min = 3, max = 3) private String currency = "VND";
}