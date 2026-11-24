package hcmute.edu.vn.backend.dto;

import java.util.List;

/** DTO của lịch sử thao tác chuyến đi. */
public final class ActivityDtos {

    private ActivityDtos() {}

    /**
     * Một dòng lịch sử. Frontend tự ghép câu tiếng Việt từ action + label + detail.
     * createdAt là thời điểm UTC dạng ISO-8601 có chữ Z, vd 2026-11-23T07:20:00.123Z.
     */
    public record ActivityItem(
            Long id,
            String action,
            Long actorId,
            String actorName,
            String actorUsername,
            String actorAvatar,
            String targetType,
            Long targetId,
            String label,
            String detail,
            String createdAt) {}

    /** nextBefore != null nghĩa là còn dòng cũ hơn; gửi lại làm tham số `before` để lấy trang tiếp. */
    public record ActivityPage(List<ActivityItem> items, Long nextBefore) {}
}
