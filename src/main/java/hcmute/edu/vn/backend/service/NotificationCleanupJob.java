package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** Mỗi đêm xóa thông báo ĐÃ ĐỌC cũ hơn 90 ngày. Thông báo chưa đọc được giữ lại. */
@Configuration
@EnableScheduling
public class NotificationCleanupJob {

    static final int RETENTION_DAYS = 90;
    private static final Logger log = LoggerFactory.getLogger(NotificationCleanupJob.class);

    private final NotificationRepository repository;

    public NotificationCleanupJob(NotificationRepository repository) {
        this.repository = repository;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeOldReadNotifications() {
        try {
            int deleted = repository.deleteReadOlderThan(LocalDateTime.now().minusDays(RETENTION_DAYS));
            if (deleted > 0) {
                log.info("Đã xóa {} thông báo đã đọc cũ hơn {} ngày", deleted, RETENTION_DAYS);
            }
        } catch (RuntimeException e) {
            log.warn("Dọn thông báo cũ thất bại: {}", e.toString());
        }
    }
}
