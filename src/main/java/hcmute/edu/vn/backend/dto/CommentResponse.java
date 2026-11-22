package hcmute.edu.vn.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class CommentResponse {
    private Long id;
    private String content;

    // Với trả lời (reply): luôn là id của bình luận GỐC (chỉ có 2 cấp).
    private Long parentId;

    // Tác giả bình luận
    private String authorName;
    private String authorUsername;
    private String authorAvatar;

    // Đánh dấu để Frontend biết đây là bình luận của chính người đang đăng nhập.
    // Field tên myComment, JSON vẫn là "isMyComment" như frontend đang dùng.
    @JsonProperty("isMyComment")
    private boolean myComment;

    // Người đang xem có được xóa không: chủ bình luận HOẶC chủ bài viết.
    private boolean canDelete;

    // Đã chỉnh sửa sau khi đăng (để hiện chữ "đã chỉnh sửa").
    private boolean edited;

    private LocalDateTime createdAt;

    // Danh sách các bình luận con (Replies), cũ nhất lên đầu
    private List<CommentResponse> replies = new ArrayList<>();
}