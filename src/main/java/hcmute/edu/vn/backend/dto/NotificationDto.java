package hcmute.edu.vn.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/** DTO của /api/notifications. */
public final class NotificationDto {

    private NotificationDto() {
    }

    /** Người gây ra thông báo (thông tin hiện tại, không lộ email / số điện thoại). */
    public record Actor(Long id, String username, String fullName, String avatar) {
    }

    public record Item(
            Long id,
            String type,
            String targetType,
            Long targetId,
            String link,
            String message,
            int actorCount,
            boolean read,
            LocalDateTime createdAt,
            LocalDateTime eventAt,
            Actor actor
    ) {
    }

    public record PageResult(
            List<Item> items,
            int page,
            int size,
            long total,
            boolean hasMore,
            long unreadCount
    ) {
    }
}
