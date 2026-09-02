package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReviewRequest {
    private Byte rating; // Từ 1 đến 5 sao
    private String comment;
}