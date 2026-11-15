package hcmute.edu.vn.backend.dto;

import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class PostResponse {
    private Long id;

    // Thông tin Tác giả
    private String authorName;
    private String authorUsername;
    private String authorAvatar;

    // Trạng thái quan hệ với Người đang xem (Người gọi API)
    private boolean followedByMe;  // Mình có đang theo dõi Tác giả không?
    private boolean followingMe;   // Tác giả có đang theo dõi Mình không?
    private String relationship;   // SELF, NONE, FOLLOWING, FOLLOWED_BY, FRIENDS

    // Nội dung bài
    private String caption;
    private String locationName;
    private Double latitude;
    private Double longitude;
    private Integer rating;
    private LocalDateTime createdAt;

    private String visibility; // PUBLIC, FRIENDS, FOLLOWERS, PRIVATE

    private List<String> imageUrls;
    private List<String> videoUrls;
}