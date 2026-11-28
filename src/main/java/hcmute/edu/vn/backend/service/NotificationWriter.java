package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.Notification;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.PostComment;
import hcmute.edu.vn.backend.entity.Trip;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.NotificationRepository;
import hcmute.edu.vn.backend.repository.PostCommentRepository;
import hcmute.edu.vn.backend.repository.PostRepository;
import hcmute.edu.vn.backend.repository.TripRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Tạo / gộp thông báo. Mỗi phương thức chạy trong transaction RIÊNG (REQUIRES_NEW) để lỗi ở đây
 * không bao giờ làm hỏng thao tác chính (theo dõi, ẩn nội dung...). Không gọi trực tiếp từ nghiệp vụ:
 * dùng {@link NotificationEvents}.
 */
@Component
public class NotificationWriter {

    static final int MAX_MESSAGE_LENGTH = 500;

    private final NotificationRepository repository;
    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final PostCommentRepository commentRepository;
    private final TripRepository tripRepository;

    public NotificationWriter(NotificationRepository repository,
                              UserRepository userRepository,
                              PostRepository postRepository,
                              PostCommentRepository commentRepository,
                              TripRepository tripRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.postRepository = postRepository;
        this.commentRepository = commentRepository;
        this.tripRepository = tripRepository;
    }

    // =====================================================================
    // MẠNG XÃ HỘI
    // =====================================================================

    /** Có người theo dõi. Theo dõi lại sau khi hủy KHÔNG tạo thông báo mới (chống làm phiền). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void followed(Long actorId, Long targetId) {
        if (actorId == null || targetId == null || actorId.equals(targetId)) {
            return;
        }
        User actor = userRepository.findById(actorId).orElse(null);
        if (actor == null || !isActive(targetId)) {
            return;
        }
        String key = "F:" + actorId;
        if (repository.existsByRecipientIdAndDedupeKey(targetId, key)) {
            return;
        }
        Notification n = base(targetId, actorId, Notification.FOLLOW, "USER", actorId, key);
        n.setLink(profileLink(actor.getUsername(), null));
        insertIgnoringDuplicate(n);
    }

    /** Có người thích bài viết. Gộp thành một thông báo cho mỗi bài: "A và N người khác đã thích bài viết". */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void postLiked(Long actorId, Long postId) {
        if (actorId == null || postId == null) {
            return;
        }
        Post post = postRepository.findById(postId).orElse(null);
        if (post == null || !isVisiblePost(post) || post.getUser() == null) {
            return;
        }
        Long ownerId = post.getUser().getId();
        if (actorId.equals(ownerId) || !isActive(ownerId)) {
            return;
        }
        int count = (int) Math.max(1, repository.countPostLikesExcludingOwner(postId, ownerId));
        String key = "L:" + postId;

        Notification existing = repository.findByRecipientAndDedupeKeyForUpdate(ownerId, key).orElse(null);
        if (existing != null) {
            // Thích lại sau khi bỏ thích (số người không đổi, cùng người) -> không đẩy thông báo lên nữa.
            if (actorId.equals(existing.getActorId()) && count <= existing.getActorCount()) {
                return;
            }
            existing.setActorId(actorId);
            existing.setActorCount(count);
            existing.setSeen(false);
            existing.setEventAt(LocalDateTime.now());
            repository.save(existing);
            return;
        }
        Notification n = base(ownerId, actorId, Notification.POST_LIKE, "POST", postId, key);
        n.setActorCount(count);
        n.setLink(postLink(post));
        insertOrMerge(n, existing2 -> {
            existing2.setActorId(actorId);
            existing2.setActorCount(count);
            existing2.setSeen(false);
            existing2.setEventAt(LocalDateTime.now());
        });
    }

    /**
     * Có bình luận mới. Là phản hồi -> báo cho tác giả bình luận gốc (COMMENT_REPLY);
     * báo cho chủ bài (POST_COMMENT) trừ khi người đó chính là người vừa nhận thông báo phản hồi.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void commentCreated(Long commentId) {
        if (commentId == null) {
            return;
        }
        PostComment comment = commentRepository.findById(commentId).orElse(null);
        if (comment == null || !"PUBLISHED".equals(comment.getStatus()) || comment.getUser() == null) {
            return;
        }
        Post post = comment.getPost();
        if (post == null || !isVisiblePost(post) || post.getUser() == null) {
            return;
        }
        Long actorId = comment.getUser().getId();
        Long ownerId = post.getUser().getId();
        Long repliedUserId = null;

        if (comment.getParentId() != null) {
            PostComment parent = commentRepository.findById(comment.getParentId()).orElse(null);
            if (parent != null && parent.getUser() != null && "PUBLISHED".equals(parent.getStatus())) {
                Long parentAuthorId = parent.getUser().getId();
                if (!parentAuthorId.equals(actorId) && isActive(parentAuthorId)) {
                    Notification reply = base(parentAuthorId, actorId, Notification.COMMENT_REPLY,
                            "POST_COMMENT", comment.getId(), "CR:" + comment.getId());
                    reply.setLink(postLink(post));
                    insertIgnoringDuplicate(reply);
                    repliedUserId = parentAuthorId;
                }
            }
        }

        if (!ownerId.equals(actorId) && !ownerId.equals(repliedUserId) && isActive(ownerId)) {
            Notification n = base(ownerId, actorId, Notification.POST_COMMENT,
                    "POST_COMMENT", comment.getId(), "C:" + comment.getId());
            n.setLink(postLink(post));
            insertIgnoringDuplicate(n);
        }
    }

    /**
     * Có người sao chép lịch trình. Gộp thành một thông báo cho mỗi chuyến đi:
     * "A và N người khác đã sao chép lịch trình của bạn". Một người chép nhiều lần không bị đếm lặp.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void tripCopied(Long actorId, Long sourceTripId) {
        if (actorId == null || sourceTripId == null) {
            return;
        }
        Trip trip = tripRepository.findById(sourceTripId).orElse(null);
        if (trip == null || trip.getUser() == null) {
            return;
        }
        Long ownerId = trip.getUser().getId();
        if (actorId.equals(ownerId) || !isActive(ownerId)) {
            return;
        }
        int count = (int) Math.max(1, tripRepository.countDistinctCopiers(sourceTripId, ownerId));
        String key = "TC:" + sourceTripId;

        Notification n = base(ownerId, actorId, Notification.TRIP_COPIED, "TRIP", sourceTripId, key);
        n.setActorCount(count);
        n.setLink("/trips/" + sourceTripId);
        n.setMessage(truncate(trip.getTitle()));
        insertOrMerge(n, existing -> {
            // Cùng người chép lại (số người không đổi) -> không đẩy thông báo lên nữa.
            if (actorId.equals(existing.getActorId()) && count <= existing.getActorCount()) {
                return;
            }
            existing.setActorId(actorId);
            existing.setActorCount(count);
            existing.setMessage(truncate(trip.getTitle()));
            existing.setSeen(false);
            existing.setEventAt(LocalDateTime.now());
        });
    }

    /**
     * Có người sao chép lịch trình trong bài viết -> báo cho tác giả bài, link tới bài.
     * Gộp một thông báo cho mỗi bài: "A và N người khác đã sao chép lịch trình trong bài viết của bạn".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void postTripCopied(Long actorId, Long postId) {
        if (actorId == null || postId == null) {
            return;
        }
        Post post = postRepository.findById(postId).orElse(null);
        if (post == null || !isVisiblePost(post) || post.getUser() == null) {
            return;
        }
        Long authorId = post.getUser().getId();
        if (actorId.equals(authorId) || !isActive(authorId)) {
            return;
        }
        int count = (int) Math.max(1, tripRepository.countDistinctCopiersOfPost(postId, authorId));
        String title = post.getTripSnapshot() != null ? truncate(post.getTripSnapshot().title()) : null;

        Notification n = base(authorId, actorId, Notification.TRIP_COPIED, "POST", postId, "TCP:" + postId);
        n.setActorCount(count);
        n.setLink(postLink(post));
        n.setMessage(title);
        insertOrMerge(n, existing -> {
            if (actorId.equals(existing.getActorId()) && count <= existing.getActorCount()) {
                return;
            }
            existing.setActorId(actorId);
            existing.setActorCount(count);
            existing.setMessage(title);
            existing.setSeen(false);
            existing.setEventAt(LocalDateTime.now());
        });
    }

    // =====================================================================
    // KIỂM DUYỆT
    // =====================================================================

    /**
     * Nội dung bị ẩn / được khôi phục -> báo cho tác giả (kèm lý do). Không lộ danh tính người kiểm duyệt.
     *
     * @param postId id bài viết liên quan (bài viết hoặc bài chứa bình luận), null với đánh giá địa điểm.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void contentModerated(boolean hidden, String targetType, Long targetId,
                                 Long authorId, Long postId, String reason) {
        if (targetType == null || targetId == null || authorId == null) {
            return;
        }
        User author = userRepository.findById(authorId).orElse(null);
        if (author == null) {
            return;
        }
        String type = hidden ? Notification.CONTENT_HIDDEN : Notification.CONTENT_RESTORED;
        String key = (hidden ? "H:" : "R:") + targetType + ":" + targetId;

        Notification n = base(authorId, null, type, targetType, targetId, key);
        n.setMessage(truncate(reason));
        n.setLink(moderationLink(targetType, author, postId));
        upsertFresh(n);
    }

    /** Báo cáo của người dùng đã được xem xét (thông báo chung, không nêu kết quả hay người xử lý). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reportsReviewed(List<Long> reporterIds, String targetType, Long targetId, Long exceptUserId) {
        if (reporterIds == null || targetType == null || targetId == null) {
            return;
        }
        for (Long reporterId : reporterIds.stream().distinct().toList()) {
            if (reporterId == null || reporterId.equals(exceptUserId) || !isActive(reporterId)) {
                continue;
            }
            Notification n = base(reporterId, null, Notification.REPORT_REVIEWED,
                    targetType, targetId, "RR:" + targetType + ":" + targetId);
            upsertFresh(n);
        }
    }

    // =====================================================================
    // HELPER
    // =====================================================================

    private Notification base(Long recipientId, Long actorId, String type, String targetType,
                              Long targetId, String dedupeKey) {
        Notification n = new Notification();
        n.setRecipientId(recipientId);
        n.setActorId(actorId);
        n.setType(type);
        n.setTargetType(targetType);
        n.setTargetId(targetId);
        n.setDedupeKey(dedupeKey);
        return n;
    }

    /** Có bản cũ cùng khóa thì làm mới (chưa đọc, đẩy lên đầu); chưa có thì tạo mới. */
    private void upsertFresh(Notification fresh) {
        insertOrMerge(fresh, existing -> {
            existing.setType(fresh.getType());
            existing.setActorId(fresh.getActorId());
            existing.setLink(fresh.getLink());
            existing.setMessage(fresh.getMessage());
            existing.setActorCount(1);
            existing.setSeen(false);
            existing.setEventAt(LocalDateTime.now());
        });
    }

    private void insertOrMerge(Notification fresh, java.util.function.Consumer<Notification> onExisting) {
        Optional<Notification> existing =
                repository.findByRecipientAndDedupeKeyForUpdate(fresh.getRecipientId(), fresh.getDedupeKey());
        if (existing.isPresent()) {
            onExisting.accept(existing.get());
            repository.save(existing.get());
            return;
        }
        try {
            repository.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException race) {
            // Hai sự kiện cùng lúc: bản kia đã tạo -> cập nhật bản đó.
            repository.findByRecipientAndDedupeKeyForUpdate(fresh.getRecipientId(), fresh.getDedupeKey())
                    .ifPresent(e -> {
                        onExisting.accept(e);
                        repository.save(e);
                    });
        }
    }

    private void insertIgnoringDuplicate(Notification n) {
        if (n.getDedupeKey() != null
                && repository.existsByRecipientIdAndDedupeKey(n.getRecipientId(), n.getDedupeKey())) {
            return;
        }
        try {
            repository.saveAndFlush(n);
        } catch (DataIntegrityViolationException ignored) {
            // Đã có bản ghi cùng khóa: bỏ qua.
        }
    }

    private boolean isActive(Long userId) {
        return userRepository.findById(userId)
                .map(u -> "ACTIVE".equalsIgnoreCase(u.getStatus()))
                .orElse(false);
    }

    private boolean isVisiblePost(Post post) {
        return "PUBLISHED".equals(post.getStatus()) && !"HIDDEN".equals(post.getModerationStatus());
    }

    /**
     * Bài viết nằm ở hồ sơ tác giả; bình luận nằm ở hồ sơ chủ bài (không phải hồ sơ người bình luận);
     * đánh giá địa điểm mở hồ sơ tác giả.
     */
    private String moderationLink(String targetType, User author, Long postId) {
        if ("POST_COMMENT".equals(targetType)) {
            if (postId == null) {
                return null;
            }
            return postRepository.findById(postId)
                    .filter(post -> post.getUser() != null)
                    .map(post -> profileLink(post.getUser().getUsername(), postId))
                    .orElse(null);
        }
        return profileLink(author.getUsername(), "POST".equals(targetType) ? postId : null);
    }

    static String profileLink(String username, Long postId) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return "/profile/" + username + (postId == null ? "" : "?post=" + postId);
    }

    private String postLink(Post post) {
        return profileLink(post.getUser().getUsername(), post.getId());
    }

    static String truncate(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > MAX_MESSAGE_LENGTH ? t.substring(0, MAX_MESSAGE_LENGTH) : t;
    }
}
