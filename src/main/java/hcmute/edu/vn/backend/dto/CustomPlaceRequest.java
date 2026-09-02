package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CustomPlaceRequest {
    private String name;
    private String description;
    private Double latitude;
    private Double longitude;
    private String images;
}