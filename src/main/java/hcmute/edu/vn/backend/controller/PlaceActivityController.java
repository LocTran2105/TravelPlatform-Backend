package hcmute.edu.vn.backend.controller;

import hcmute.edu.vn.backend.repository.PlaceReviewRepository;
import hcmute.edu.vn.backend.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Hoạt động cộng đồng quanh các địa điểm của chuyến đi (dòng thông báo dưới thẻ xem nhanh trên bản đồ):
 * số bài đăng công khai, số đánh giá, điểm trung bình và số lượng mới trong {@value #RECENT_DAYS} ngày gần đây.
 * Bài đăng/đánh giá đang liên kết với địa điểm theo TÊN, nên kết quả là con số tham khảo.
 */
@RestController
@RequestMapping("/api/v1/places")
@RequiredArgsConstructor
public class PlaceActivityController {

    private static final int RECENT_DAYS = 30;
    private static final int MAX_NAMES = 60;
    private static final int MAX_NAME_LENGTH = 255;

    private final PostRepository postRepository;
    private final PlaceReviewRepository placeReviewRepository;

    public record ActivityRequest(List<String> names) { }

    public record PlaceActivity(long posts, long recentPosts, long reviews, long recentReviews, BigDecimal avgRating) { }

    @PostMapping("/activity")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, PlaceActivity>> activity(@RequestBody ActivityRequest request) {
        Set<String> names = new LinkedHashSet<>();
        if (request != null && request.names() != null) {
            for (String raw : request.names()) {
                if (raw == null || raw.isBlank()) continue;
                String name = raw.trim();
                if (name.length() <= MAX_NAME_LENGTH) names.add(name);
                if (names.size() >= MAX_NAMES) break;
            }
        }
        if (names.isEmpty()) return ResponseEntity.ok(Map.of());

        LocalDateTime since = LocalDateTime.now().minusDays(RECENT_DAYS);

        Map<String, Object[]> reviewStats = new LinkedHashMap<>();
        for (Object[] row : placeReviewRepository.activityByLocationNames(names, since)) {
            if (row != null && row.length >= 4 && row[0] != null) {
                reviewStats.put(String.valueOf(row[0]).toLowerCase(Locale.ROOT), row);
            }
        }

        Map<String, PlaceActivity> result = new LinkedHashMap<>();
        for (String name : names) {
            long posts = 0;
            long recentPosts = 0;
            List<Object[]> postRows = postRepository.countPublicPostsByLocation(likePattern(name), since);
            if (!postRows.isEmpty() && postRows.get(0) != null) {
                posts = asLong(postRows.get(0)[0]);
                recentPosts = asLong(postRows.get(0)[1]);
            }

            long reviews = 0;
            long recentReviews = 0;
            BigDecimal avg = null;
            Object[] review = reviewStats.get(name.toLowerCase(Locale.ROOT));
            if (review != null) {
                reviews = asLong(review[2]);
                recentReviews = asLong(review[3]);
                if (review[1] instanceof Number number) {
                    avg = BigDecimal.valueOf(number.doubleValue()).setScale(1, RoundingMode.HALF_UP);
                }
            }

            if (posts > 0 || reviews > 0) {
                result.put(name, new PlaceActivity(posts, recentPosts, reviews, recentReviews, avg));
            }
        }
        return ResponseEntity.ok(result);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** "%tên%" viết thường, escape ký tự đặc biệt của LIKE bằng '!' (khớp ESCAPE '!' trong query). */
    private static String likePattern(String name) {
        String escaped = name.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}