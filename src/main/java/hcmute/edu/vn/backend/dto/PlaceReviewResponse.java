package hcmute.edu.vn.backend.dto;

import lombok.Getter; import lombok.Setter;
import java.time.LocalDateTime;

@Getter @Setter
public class PlaceReviewResponse {
    private Long id;
    private String authorName;
    private String authorAvatar;
    private String content;
    private Integer rating;
    private LocalDateTime createdAt;
    private long likesCount;
    private boolean isLikedByMe; // True nếu user hiện tại đã thả tim đánh giá này
}