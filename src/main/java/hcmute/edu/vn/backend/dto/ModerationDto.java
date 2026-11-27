package hcmute.edu.vn.backend.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Các DTO của module kiểm duyệt. */
public final class ModerationDto {

    private ModerationDto() {}

    /** Body báo cáo của người dùng: POST /api/reports */
    public record ReportRequest(String targetType, Long targetId, String reasonCode, String details) {}

    /** Body của ẩn / khôi phục / bỏ qua. reason có thể null (chỉ ẩn mới bắt buộc). */
    public record ActionRequest(String reason) {}

    public record Stats(
            long pendingContents,               // số nội dung đang có báo cáo chờ xử lý
            long pendingReports,                // tổng số báo cáo chờ xử lý
            Map<String, Long> pendingByType,    // POST / POST_COMMENT / PLACE_REVIEW -> số nội dung
            long actionsToday                   // số thao tác kiểm duyệt từ 00:00 hôm nay
    ) {}

    public record PageResult<T>(List<T> items, int page, int size, long total) {}

    public record AuthorInfo(Long id, String fullName, String username, String avatar, String role) {}

    public record MediaItem(String url, String type) {}

    /** Một dòng trong hàng đợi (đã gộp theo nội dung bị báo cáo). */
    public record QueueItem(
            String targetType,
            Long targetId,
            long reportCount,
            LocalDateTime lastReportedAt,
            Map<String, Long> reasons,          // reasonCode -> số báo cáo
            boolean unavailable,                // nội dung đã bị xóa / không còn
            boolean hidden,
            String excerpt,
            AuthorInfo author
    ) {}

    public record ReportInfo(
            Long id,
            String reasonCode,
            String details,
            String status,                      // PENDING / RESOLVED / REJECTED
            String resolution,                  // HIDDEN / DISMISSED / null
            String reporterName,
            String reporterUsername,
            LocalDateTime createdAt,
            LocalDateTime reviewedAt
    ) {}

    public record ActionInfo(
            Long id,
            String action,                      // HIDE / RESTORE / DISMISS
            String targetType,
            Long targetId,
            String reason,
            int reportCount,
            String actorName,
            String actorUsername,
            String targetAuthorName,
            LocalDateTime createdAt
    ) {}

    /** Chi tiết một nội dung bị báo cáo, kèm báo cáo và lịch sử xử lý. */
    public record TargetDetail(
            String targetType,
            Long targetId,
            boolean unavailable,
            boolean hidden,
            String text,
            Integer rating,                     // đánh giá địa điểm (có thể null)
            String locationName,                // bài viết / đánh giá địa điểm (có thể null)
            Long postId,                        // bình luận thuộc bài nào (có thể null)
            List<MediaItem> media,
            LocalDateTime createdAt,
            AuthorInfo author,
            String moderationNote,
            LocalDateTime moderatedAt,
            boolean canModerate,                // người đang xem có được ẩn / khôi phục không
            String cannotModerateReason,        // lý do không được (null nếu được)
            List<ReportInfo> reports,
            List<ActionInfo> history
    ) {}
}
