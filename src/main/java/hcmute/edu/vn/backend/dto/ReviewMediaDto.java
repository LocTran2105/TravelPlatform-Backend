package hcmute.edu.vn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ReviewMediaDto {
    private String url;
    private String type; // IMAGE | VIDEO
}