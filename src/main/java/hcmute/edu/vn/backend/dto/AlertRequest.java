package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AlertRequest {
    private String title;
    private String description;
    private String alertType;
    private Double latitude;
    private Double longitude;
}