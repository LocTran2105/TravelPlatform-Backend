package hcmute.edu.vn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
public class ReviewResponse {
    private Long id;
    private Long userId;
    private String userFullName;
    private String userAvatar;
    private Byte rating;
    private String comment;
    private LocalDateTime createdAt;
}