package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.ModerationDto.MediaItem;
import hcmute.edu.vn.backend.entity.Image;
import hcmute.edu.vn.backend.entity.PlaceReview;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.PostComment;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PlaceReviewMediaRepository;
import hcmute.edu.vn.backend.repository.PlaceReviewRepository;
import hcmute.edu.vn.backend.repository.PostCommentRepository;
import hcmute.edu.vn.backend.repository.PostRepository;
import hcmute.edu.vn.backend.repository.PostStatsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Nơi DUY NHẤT biết cách đọc / ẩn / khôi phục từng loại nội dung bị báo cáo.
 * Thêm loại mới (ví dụ ảnh bìa, hồ sơ) chỉ cần thêm một nhánh ở đây.
 */
@Component
@RequiredArgsConstructor
public class ModerationTargets {

    public static final String POST = "POST";
    public static final String POST_COMMENT = "POST_COMMENT";
    public static final String PLACE_REVIEW = "PLACE_REVIEW";

    private final PostRepository postRepository;
    private final PostCommentRepository postCommentRepository;
    private final PlaceReviewRepository placeReviewRepository;
    private final PlaceReviewMediaRepository placeReviewMediaRepository;
    private final PostStatsRepository postStatsRepository;

    /**
     * Ảnh chụp trạng thái một nội dung. exists = false khi nội dung không còn
     * (bị xóa cứng, hoặc bài viết đã xóa mềm) - lúc đó chỉ còn xem được báo cáo.
     */
    public record Target(
            String type,
            Long id,
            boolean exists,
            boolean hidden,
            User author,
            String text,
            Integer rating,
            String locationName,
            Long postId,
            List<MediaItem> media,
            LocalDateTime createdAt,
            String moderationNote,
            LocalDateTime moderatedAt,
            Object entity
    ) {
        static Target missing(String type, Long id) {
            return new Target(type, id, false, false, null, null, null, null, null,
                    List.of(), null, null, null, null);
        }
    }

    /** Chuẩn hóa và kiểm tra loại nội dung; ném IllegalArgumentException nếu không hỗ trợ. */
    public static String normalizeType(String raw) {
        String type = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!POST.equals(type) && !POST_COMMENT.equals(type) && !PLACE_REVIEW.equals(type)) {
            throw new IllegalArgumentException("Loại nội dung không được hỗ trợ: " + raw);
        }
        return type;
    }

    /** Đọc nội dung. forUpdate = true thì khóa dòng (bài viết) để hai người không ẩn cùng lúc. */
    public Target load(String type, Long id, boolean forUpdate) {
        return switch (type) {
            case POST -> loadPost(id, forUpdate);
            case POST_COMMENT -> loadComment(id);
            case PLACE_REVIEW -> loadReview(id);
            default -> throw new IllegalArgumentException("Loại nội dung không được hỗ trợ: " + type);
        };
    }

    /** Ẩn nội dung. Gọi sau khi đã kiểm tra quyền. */
    public void hide(Target target, User actor, String note) {
        LocalDateTime now = LocalDateTime.now();
        switch (target.type()) {
            case POST -> {
                Post post = (Post) target.entity();
                post.setModerationStatus("HIDDEN");
                post.setModeratedBy(actor);
                post.setModerationNote(note);
                post.setModeratedAt(now);
                postRepository.save(post);
            }
            case POST_COMMENT -> {
                PostComment comment = (PostComment) target.entity();
                comment.setStatus("HIDDEN");
                comment.setModeratedBy(actor);
                comment.setModerationNote(note);
                comment.setModeratedAt(now);
                postCommentRepository.save(comment);
            }
            case PLACE_REVIEW -> {
                PlaceReview review = (PlaceReview) target.entity();
                review.setStatus("HIDDEN");
                review.setModeratedBy(actor);
                review.setModerationNote(note);
                review.setModeratedAt(now);
                placeReviewRepository.save(review);
            }
            default -> throw new IllegalArgumentException("Loại nội dung không được hỗ trợ.");
        }
    }

    /** Khôi phục nội dung đã ẩn. */
    public void restore(Target target, User actor, String note) {
        LocalDateTime now = LocalDateTime.now();
        switch (target.type()) {
            case POST -> {
                Post post = (Post) target.entity();
                post.setModerationStatus("APPROVED");
                post.setModeratedBy(actor);
                post.setModerationNote(note);
                post.setModeratedAt(now);
                postRepository.save(post);
            }
            case POST_COMMENT -> {
                PostComment comment = (PostComment) target.entity();
                comment.setStatus("PUBLISHED");
                comment.setModeratedBy(actor);
                comment.setModerationNote(note);
                comment.setModeratedAt(now);
                postCommentRepository.save(comment);
            }
            case PLACE_REVIEW -> {
                PlaceReview review = (PlaceReview) target.entity();
                review.setStatus("PUBLISHED");
                review.setModeratedBy(actor);
                review.setModerationNote(note);
                review.setModeratedAt(now);
                placeReviewRepository.save(review);
            }
            default -> throw new IllegalArgumentException("Loại nội dung không được hỗ trợ.");
        }
    }

    // ------------------------------------------------------------------

    private Target loadPost(Long id, boolean forUpdate) {
        Optional<Post> found = forUpdate ? postRepository.findByIdForModeration(id) : postRepository.findById(id);
        if (found.isEmpty() || !"PUBLISHED".equals(found.get().getStatus())) {
            return Target.missing(POST, id);
        }
        Post post = found.get();
        List<MediaItem> media = postStatsRepository.findMediaByPostIds(List.of(post.getId())).stream()
                .map(this::toMedia)
                .toList();
        return new Target(POST, post.getId(), true,
                "HIDDEN".equals(post.getModerationStatus()),
                post.getUser(), post.getCaption(), post.getRating(), post.getLocationName(), null,
                media, post.getCreatedAt(), post.getModerationNote(), post.getModeratedAt(), post);
    }

    private Target loadComment(Long id) {
        Optional<PostComment> found = postCommentRepository.findById(id);
        if (found.isEmpty() || !"PUBLISHED".equals(found.get().getPost().getStatus())) {
            return Target.missing(POST_COMMENT, id);
        }
        PostComment comment = found.get();
        return new Target(POST_COMMENT, comment.getId(), true,
                "HIDDEN".equals(comment.getStatus()),
                comment.getUser(), comment.getContent(), null, null, comment.getPost().getId(),
                List.of(), comment.getCreatedAt(), comment.getModerationNote(), comment.getModeratedAt(), comment);
    }

    private Target loadReview(Long id) {
        Optional<PlaceReview> found = placeReviewRepository.findById(id);
        if (found.isEmpty()) {
            return Target.missing(PLACE_REVIEW, id);
        }
        PlaceReview review = found.get();
        boolean published = "PUBLISHED".equals(review.getStatus());
        boolean hidden = "HIDDEN".equals(review.getStatus());
        if (!published && !hidden) {
            // PENDING / REJECTED không thuộc luồng báo cáo: coi như không có để kiểm duyệt.
            return Target.missing(PLACE_REVIEW, id);
        }
        List<MediaItem> media = placeReviewMediaRepository.findByReviewIdInOrderByIdAsc(List.of(review.getId()))
                .stream()
                .map(m -> new MediaItem(m.getUrl(), "VIDEO".equalsIgnoreCase(m.getType()) ? "VIDEO" : "IMAGE"))
                .toList();
        return new Target(PLACE_REVIEW, review.getId(), true, hidden,
                review.getUser(), review.getContent(), review.getRating(), review.getLocationName(), null,
                media, review.getCreatedAt(), review.getModerationNote(), review.getModeratedAt(), review);
    }

    private MediaItem toMedia(Image image) {
        return new MediaItem(image.getImageUrl(),
                "VIDEO".equalsIgnoreCase(image.getMediaType()) ? "VIDEO" : "IMAGE");
    }
}
