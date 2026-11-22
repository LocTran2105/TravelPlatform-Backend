package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.PostPageResponse;
import hcmute.edu.vn.backend.dto.PostResponse;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PostRepository;
import hcmute.edu.vn.backend.repository.PostStatsRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tab "Dành cho bạn": bảng tin xếp hạng theo điểm (xem FeedRanker).
 *
 * CÁCH LÀM (2 pha):
 *  1) Pha R (ranked): lấy "bể ứng viên" = N bài mới nhất người xem được phép xem
 *     (N = feed.ranking.pool-size, mặc định 200), chấm điểm rồi phân trang theo điểm.
 *  2) Pha L (latest): khi đã hết bể ứng viên mà DB còn bài cũ hơn thì nối tiếp
 *     bằng các bài cũ hơn theo thứ tự mới -> cũ, để người dùng vẫn cuộn tiếp được.
 *
 * Con trỏ (nextCursor) là chuỗi, client chỉ việc gửi lại nguyên văn:
 *   R~maxId~asOfMillis~score~id   đang ở pha R (maxId + asOf = "ảnh chụp" lúc bắt đầu cuộn,
 *                                 để bài mới đăng / thời gian trôi không làm lệch thứ tự giữa các trang)
 *   L~id                          đang ở pha L, lấy các bài có id nhỏ hơn
 */
@Service
public class FeedService {

    public static final int DEFAULT_POOL_SIZE = 200;

    private final PostRepository postRepository;
    private final PostStatsRepository postStatsRepository;
    private final UserRepository userRepository;
    private final PostService postService;
    private final int poolSize;

    public FeedService(
            PostRepository postRepository,
            PostStatsRepository postStatsRepository,
            UserRepository userRepository,
            PostService postService,
            @Value("${feed.ranking.pool-size:" + DEFAULT_POOL_SIZE + "}") int poolSize) {
        this.postRepository = postRepository;
        this.postStatsRepository = postStatsRepository;
        this.userRepository = userRepository;
        this.postService = postService;
        this.poolSize = Math.max(1, poolSize);
    }

    // ---- con trỏ ---------------------------------------------------------

    private record RankedCursor(long maxId, long asOfMillis, double score, long id) {}

    private record Cursor(RankedCursor ranked, Long latestBefore) {}

    private Cursor parseCursor(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            String[] p = raw.split("~");
            if (p.length == 5 && p[0].equals("R")) {
                return new Cursor(new RankedCursor(
                        Long.parseLong(p[1]), Long.parseLong(p[2]),
                        Double.parseDouble(p[3]), Long.parseLong(p[4])), null);
            }
            if (p.length == 2 && p[0].equals("L")) {
                return new Cursor(null, Long.parseLong(p[1]));
            }
        } catch (NumberFormatException ignored) {
            // rơi xuống ném lỗi bên dưới
        }
        throw new IllegalArgumentException("Con trỏ phân trang không hợp lệ.");
    }

    private String rankedCursor(long maxId, long asOfMillis, FeedRanker.Scored last) {
        return "R~" + maxId + "~" + asOfMillis + "~" + last.score() + "~" + last.post().getId();
    }

    // ---- API --------------------------------------------------------------

    @Transactional(readOnly = true)
    public PostPageResponse getRankedFeedPage(String currentUserEmail, String cursorRaw, Integer size) {
        User viewer = (currentUserEmail == null || currentUserEmail.isBlank())
                ? null : userRepository.findByEmail(currentUserEmail).orElse(null);
        Long viewerId = viewer != null ? viewer.getId() : -1L;
        int pageSize = PostService.clampPageSize(size);
        Cursor cursor = parseCursor(cursorRaw);

        // Đang ở pha L: chỉ là phân trang "mới nhất" thông thường.
        if (cursor != null && cursor.latestBefore() != null) {
            return latestPhase(viewer, viewerId, cursor.latestBefore(), pageSize);
        }

        RankedCursor rc = cursor != null ? cursor.ranked() : null;
        long maxIdExclusive = rc != null ? rc.maxId() + 1 : Long.MAX_VALUE;

        List<Post> pool = postRepository.findFeedPageForViewer(
                viewerId, maxIdExclusive, PageRequest.of(0, poolSize));
        if (pool.isEmpty()) {
            return new PostPageResponse(List.of(), false, null);
        }

        // "Ảnh chụp": id lớn nhất trong bể + thời điểm bắt đầu. Các trang sau dùng lại
        // đúng hai giá trị này nên thứ tự không bị xê dịch khi cuộn.
        long maxId = rc != null ? rc.maxId() : pool.get(0).getId();
        long asOfMillis = rc != null ? rc.asOfMillis() : System.currentTimeMillis();
        boolean poolFull = pool.size() >= poolSize;
        long poolMinId = pool.get(pool.size() - 1).getId();

        List<FeedRanker.Scored> ranked = FeedRanker.rank(
                buildCandidates(pool, viewer, viewerId), Instant.ofEpochMilli(asOfMillis));

        List<FeedRanker.Scored> remaining = rc == null
                ? ranked
                : ranked.stream().filter(s -> FeedRanker.isAfter(s, rc.score(), rc.id())).toList();

        List<FeedRanker.Scored> taken = remaining.subList(0, Math.min(pageSize, remaining.size()));
        List<Post> pagePosts = new ArrayList<>(taken.stream().map(FeedRanker.Scored::post).toList());

        // Còn bài đã chấm điểm chưa hiện -> còn trang sau ở pha R.
        if (remaining.size() > pageSize) {
            String next = rankedCursor(maxId, asOfMillis, taken.get(taken.size() - 1));
            return new PostPageResponse(postService.toResponses(pagePosts, viewer), true, next);
        }

        // Hết bài đã chấm điểm. Bể chưa đầy = DB không còn bài nào cũ hơn -> kết thúc.
        if (!poolFull) {
            return new PostPageResponse(postService.toResponses(pagePosts, viewer), false, null);
        }

        // Bể đầy: có thể còn bài cũ hơn -> lấp chỗ trống của trang bằng pha L.
        int need = pageSize - pagePosts.size();
        List<Post> older = postRepository.findFeedPageForViewer(
                viewerId, poolMinId, PageRequest.of(0, need + 1));
        boolean hasMore = older.size() > need;
        List<Post> filler = hasMore ? older.subList(0, need) : older;
        pagePosts.addAll(filler);

        String next = null;
        if (hasMore) {
            long lastId = filler.isEmpty() ? poolMinId : filler.get(filler.size() - 1).getId();
            next = "L~" + lastId;
        }
        return new PostPageResponse(postService.toResponses(pagePosts, viewer), hasMore, next);
    }

    private PostPageResponse latestPhase(User viewer, Long viewerId, long before, int pageSize) {
        List<Post> rows = postRepository.findFeedPageForViewer(
                viewerId, before, PageRequest.of(0, pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        List<Post> page = hasMore ? rows.subList(0, pageSize) : rows;
        String next = hasMore ? "L~" + page.get(page.size() - 1).getId() : null;
        List<PostResponse> items = postService.toResponses(page, viewer);
        return new PostPageResponse(items, hasMore, next);
    }

    // ---- dữ liệu cho chấm điểm (truy vấn gộp, không N+1) -----------------------

    private List<FeedRanker.Candidate> buildCandidates(List<Post> pool, User viewer, Long viewerId) {
        List<Long> postIds = pool.stream().map(Post::getId).toList();
        Map<Long, Long> likes = toCountMap(postStatsRepository.countLikesByPostIds(postIds));
        Map<Long, Long> comments = toCountMap(postStatsRepository.countCommentsByPostIds(postIds));

        Set<Long> following = new HashSet<>();
        Set<Long> followers = new HashSet<>();
        if (viewer != null) {
            List<Long> authorIds = pool.stream().map(p -> p.getUser().getId()).distinct().toList();
            following.addAll(postStatsRepository.findFollowedAuthorIds(viewerId, authorIds));
            followers.addAll(postStatsRepository.findFollowerAuthorIds(viewerId, authorIds));
        }

        List<FeedRanker.Candidate> result = new ArrayList<>(pool.size());
        for (Post post : pool) {
            Long authorId = post.getUser().getId();
            String relation;
            if (viewer == null) relation = "NONE";
            else if (authorId.equals(viewerId)) relation = "SELF";
            else if (following.contains(authorId) && followers.contains(authorId)) relation = "FRIENDS";
            else if (following.contains(authorId)) relation = "FOLLOWING";
            else if (followers.contains(authorId)) relation = "FOLLOWED_BY";
            else relation = "NONE";

            result.add(new FeedRanker.Candidate(
                    post, likes.getOrDefault(post.getId(), 0L), comments.getOrDefault(post.getId(), 0L), relation));
        }
        return result;
    }

    private Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return map;
    }
}