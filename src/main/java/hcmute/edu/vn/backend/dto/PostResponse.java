package hcmute.edu.vn.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    // Giữ lại để các màn hình cũ (trang cá nhân...) vẫn chạy.
    private List<String> imageUrls;
    private List<String> videoUrls;

    // MỚI: toàn bộ ảnh/video theo ĐÚNG thứ tự người dùng đã chọn khi đăng.
    private List<MediaItem> media;

    // Tim + bình luận.
    // Field tên likedByMe, JSON vẫn là "isLikedByMe" như frontend đang dùng
    // (đặt @JsonProperty trên field cùng tên với getter nên chỉ ra MỘT key,
    // không bị trùng "likedByMe" + "isLikedByMe" như trước).
    @JsonProperty("isLikedByMe")
    private boolean likedByMe;
    private long likeCount;
    private long commentCount;

    /** type: "IMAGE" hoặc "VIDEO". */
    public record MediaItem(String url, String type) {}
}