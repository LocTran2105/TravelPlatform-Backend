package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.Post;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Chấm điểm bài viết cho tab "Dành cho bạn". Chỉ là hàm thuần (không đụng DB)
 * nên dễ chỉnh và dễ kiểm thử.
 *
 *   điểm = (1 + tim*1 + bình_luận*2) ^ 0.6 × hệ_số_quan_hệ ÷ (tuổi_giờ + 2) ^ 1.3
 *            └─ tương tác (lũy thừa 0.6 để       └─ bài càng cũ điểm càng giảm
 *               bài viral không áp đảo)
 *
 * rồi nhân thêm 0.85^k cho bài thứ k (k = 0, 1, 2...) của CÙNG một tác giả
 * để một người đăng liên tục không chiếm hết bảng tin.
 *
 * Muốn đổi "gu" của feed chỉ cần sửa các hằng số bên dưới:
 *   - tăng GRAVITY          -> ưu tiên bài mới hơn
 *   - tăng COMMENT_WEIGHT   -> bài nhiều thảo luận nổi hơn
 *   - tăng hệ số FRIENDS... -> bài của người quen nổi hơn
 */
public final class FeedRanker {

    static final double GRAVITY = 1.3;
    static final double HOURS_OFFSET = 2.0;
    static final double LIKE_WEIGHT = 1.0;
    static final double COMMENT_WEIGHT = 2.0;
    static final double ENGAGEMENT_EXPONENT = 0.6;
    static final double AUTHOR_REPEAT_DECAY = 0.85;

    private FeedRanker() {}

    /** relation: SELF, FRIENDS, FOLLOWING, FOLLOWED_BY, NONE (giống PostResponse.relationship). */
    public record Candidate(Post post, long likes, long comments, String relation) {}

    public record Scored(Post post, double score) {}

    static double relationBoost(String relation) {
        if (relation == null) return 1.0;
        return switch (relation) {
            case "FRIENDS" -> 1.6;      // theo dõi chéo: ưu tiên cao nhất
            case "FOLLOWING" -> 1.4;    // mình theo dõi họ
            case "FOLLOWED_BY" -> 1.1;  // họ theo dõi mình
            case "SELF" -> 1.0;
            default -> 1.0;             // chưa có quan hệ
        };
    }

    static double rawScore(Candidate c, Instant asOf) {
        double engagement = Math.pow(
                1.0 + c.likes() * LIKE_WEIGHT + c.comments() * COMMENT_WEIGHT, ENGAGEMENT_EXPONENT);
        double ageHours = ageHours(c.post(), asOf);
        return engagement * relationBoost(c.relation())
                / Math.pow(ageHours + HOURS_OFFSET, GRAVITY);
    }

    private static double ageHours(Post post, Instant asOf) {
        LocalDateTime created = post.getCreatedAt();
        if (created == null) return 0.0;
        Instant createdAt = created.atZone(ZoneId.systemDefault()).toInstant();
        long seconds = Duration.between(createdAt, asOf).getSeconds();
        return Math.max(0, seconds) / 3600.0;
    }

    /**
     * Trả về danh sách đã sắp xếp: điểm giảm dần, bằng điểm thì id lớn (mới) trước.
     * Kết quả phụ thuộc DUY NHẤT vào đầu vào + asOf nên gọi lại cho ra đúng thứ tự cũ
     * (cần cho phân trang bằng con trỏ).
     */
    public static List<Scored> rank(List<Candidate> candidates, Instant asOf) {
        List<Scored> byRaw = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            byRaw.add(new Scored(c.post(), rawScore(c, asOf)));
        }
        byRaw.sort(COMPARATOR);

        // Giảm điểm bài thứ 2, 3... của cùng tác giả (theo thứ tự điểm gốc).
        Map<Long, Integer> seenPerAuthor = new HashMap<>();
        List<Scored> adjusted = new ArrayList<>(byRaw.size());
        for (Scored s : byRaw) {
            Long authorId = s.post().getUser().getId();
            int k = seenPerAuthor.merge(authorId, 1, Integer::sum) - 1;
            adjusted.add(new Scored(s.post(), s.score() * Math.pow(AUTHOR_REPEAT_DECAY, k)));
        }
        adjusted.sort(COMPARATOR);
        return adjusted;
    }

    /** Điểm cao trước; bằng điểm thì id lớn trước. */
    static final Comparator<Scored> COMPARATOR = (a, b) -> {
        int byScore = Double.compare(b.score(), a.score());
        return byScore != 0 ? byScore : Long.compare(b.post().getId(), a.post().getId());
    };

    /** (score, id) có nằm SAU vị trí con trỏ (cursorScore, cursorId) trong thứ tự xếp hạng không. */
    static boolean isAfter(Scored s, double cursorScore, long cursorId) {
        int byScore = Double.compare(s.score(), cursorScore);
        return byScore < 0 || (byScore == 0 && s.post().getId() < cursorId);
    }
}