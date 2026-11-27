package hcmute.edu.vn.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Cửa duy nhất để nghiệp vụ phát sự kiện thông báo.
 *
 * <ul>
 *   <li>Nếu đang trong transaction: chỉ gửi SAU KHI commit (thao tác chính bị rollback thì không có thông báo).</li>
 *   <li>Mọi lỗi đều bị nuốt và ghi log: thông báo không bao giờ làm hỏng thao tác chính.</li>
 * </ul>
 */
@Component
public class NotificationEvents {

    private static final Logger log = LoggerFactory.getLogger(NotificationEvents.class);

    private final NotificationWriter writer;

    public NotificationEvents(NotificationWriter writer) {
        this.writer = writer;
    }

    public void onFollowed(Long actorId, Long targetId) {
        dispatch("followed", () -> writer.followed(actorId, targetId));
    }

    public void onPostLiked(Long actorId, Long postId) {
        dispatch("postLiked", () -> writer.postLiked(actorId, postId));
    }

    public void onCommentCreated(Long commentId) {
        dispatch("commentCreated", () -> writer.commentCreated(commentId));
    }

    public void onContentModerated(boolean hidden, String targetType, Long targetId,
                                   Long authorId, Long postId, String reason) {
        dispatch("contentModerated",
                () -> writer.contentModerated(hidden, targetType, targetId, authorId, postId, reason));
    }

    public void onReportsReviewed(List<Long> reporterIds, String targetType, Long targetId, Long exceptUserId) {
        if (reporterIds == null || reporterIds.isEmpty()) {
            return;
        }
        List<Long> copy = List.copyOf(reporterIds);
        dispatch("reportsReviewed", () -> writer.reportsReviewed(copy, targetType, targetId, exceptUserId));
    }

    private void dispatch(String name, Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (RuntimeException e) {
                log.warn("Không tạo được thông báo ({}): {}", name, e.toString());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }
}
