package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.repository.PostRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Chạy một lần khi khởi động: bài đã gắn chuyến đi TRƯỚC khi có tính năng bản chụp (posts.trip_id có giá trị
 * nhưng trip_snapshot = NULL) được chụp từ chuyến đi hiện tại. Lỗi ở một bài chỉ ghi log, không chặn khởi động.
 */
@Component
public class PostTripSnapshotBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PostTripSnapshotBackfill.class);

    private final PostRepository postRepository;
    private final PostService postService;

    public PostTripSnapshotBackfill(PostRepository postRepository, PostService postService) {
        this.postRepository = postRepository;
        this.postService = postService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Long> ids;
        try {
            ids = postRepository.findIdsWithTripButNoSnapshot();
        } catch (RuntimeException e) {
            // Thường do DB chưa chạy migration thêm cột trip_snapshot.
            log.warn("Không kiểm tra được bản chụp lịch trình của bài viết: {}", e.toString());
            return;
        }
        if (ids.isEmpty()) return;

        int done = 0;
        for (Long id : ids) {
            try {
                if (postService.backfillTripSnapshot(id)) done++;
            } catch (RuntimeException e) {
                log.warn("Không tạo được bản chụp lịch trình cho bài {}: {}", id, e.toString());
            }
        }
        log.info("Đã tạo bản chụp lịch trình cho {}/{} bài viết cũ.", done, ids.size());
    }
}
