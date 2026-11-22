package hcmute.edu.vn.backend.dto;

import java.util.List;

/**
 * Một trang bài viết.
 *  - items      : các bài của trang này
 *  - hasMore    : còn trang sau không
 *  - nextCursor : chuỗi mờ (opaque), gửi lại NGUYÊN VĂN làm tham số "cursor" để lấy trang kế
 *                 tiếp (null nếu hết). Client không nên đọc hay tự ghép nội dung của nó.
 */
public record PostPageResponse(List<PostResponse> items, boolean hasMore, String nextCursor) {}