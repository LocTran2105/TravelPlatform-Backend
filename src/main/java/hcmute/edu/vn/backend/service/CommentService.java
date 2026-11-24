package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.dto.CommentRequest;
import hcmute.edu.vn.backend.dto.CommentResponse;
import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.PostComment;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.PostCommentRepository;
import hcmute.edu.vn.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Bình luận 2 cấp (như Facebook / Instagram): bình luận gốc + các trả lời.
 * Trả lời một trả lời vẫn được gắn vào bình luận GỐC (frontend tự thêm @tên).
 */
@Service
@RequiredArgsConstructor
public class CommentService {

    static final int MAX_CONTENT_LENGTH = 1000;

    private final PostCommentRepository postCommentRepository;
    private final UserRepository userRepository;
    private final PostAccessService postAccessService;

    // =====================================================================
    // THÊM
    // =====================================================================

    @Transactional
    public CommentResponse addComment(Long postId, CommentRequest request, String email) {
        User user = requireActiveUser(email);
        // 404 nếu bài không tồn tại, đã xóa, hoặc người này không có quyền xem.
        Post post = postAccessService.requireViewablePost(postId, user);

        String content = validateContent(request.getContent());

        PostComment comment = new PostComment();
        comment.setPost(post);
        comment.setUser(user);
        comment.setContent(content);
        comment.setParentId(resolveParentId(request.getParentId(), post));

        PostComment saved = postCommentRepository.save(comment);
        return toResponse(saved, user, post.getUser().getId(), saved.getParentId());
    }

    // =====================================================================
    // SỬA
    // =====================================================================

    @Transactional
    public CommentResponse updateComment(Long commentId, CommentRequest request, String email) {
        User user = requireActiveUser(email);
        PostComment comment = requireComment(commentId);

        if (!comment.getUser().getId().equals(user.getId())) {
            throw new AccessDeniedException("Bạn chỉ có quyền sửa bình luận của chính mình.");
        }
        // Bài đã xóa / không còn xem được thì không sửa nữa.
        Post post = postAccessService.requireViewablePost(comment.getPost().getId(), user);

        comment.setContent(validateContent(request.getContent()));
        PostComment updated = postCommentRepository.saveAndFlush(comment); // flush để lấy updatedAt mới
        return toResponse(updated, user, post.getUser().getId(), updated.getParentId());
    }

    // =====================================================================
    // XÓA
    // =====================================================================

    /**
     * Chủ bình luận HOẶC chủ bài viết được xóa.
     * Xóa bình luận gốc thì các trả lời bị xóa theo (FK ON DELETE CASCADE).
     */
    @Transactional
    public void deleteComment(Long commentId, String email) {
        User user = requireActiveUser(email);
        PostComment comment = requireComment(commentId);

        Post post = comment.getPost();
        if (!"PUBLISHED".equals(post.getStatus())) {
            throw new NoSuchElementException("Không tìm thấy bình luận.");
        }

        boolean isCommentOwner = comment.getUser().getId().equals(user.getId());
        boolean isPostOwner = post.getUser().getId().equals(user.getId());
        if (!isCommentOwner && !isPostOwner) {
            throw new AccessDeniedException("Bạn không có quyền xóa bình luận này.");
        }

        postCommentRepository.delete(comment);
    }

    // =====================================================================
    // ĐỌC
    // =====================================================================

    @Transactional(readOnly = true)
    public List<CommentResponse> getCommentsByPost(Long postId, String currentUserEmail) {
        User viewer = findUserOrNull(currentUserEmail);
        Post post = postAccessService.requireViewablePost(postId, viewer);
        Long postOwnerId = post.getUser().getId();

        List<PostComment> all = postCommentRepository.findAllByPostIdWithUser(postId);

        Map<Long, PostComment> byId = new HashMap<>();
        for (PostComment c : all) {
            byId.put(c.getId(), c);
        }

        // Lượt 1: bình luận gốc (giữ thứ tự cũ -> mới).
        Map<Long, CommentResponse> roots = new LinkedHashMap<>();
        for (PostComment c : all) {
            if (resolveRootId(c, byId) == c.getId()) {
                roots.put(c.getId(), toResponse(c, viewer, postOwnerId, null));
            }
        }

        // Lượt 2: trả lời -> gom vào bình luận gốc (kể cả dữ liệu cũ lỡ
        // trả lời lồng 3 cấp: trước đây bị mất khỏi giao diện, giờ gom về gốc).
        for (PostComment c : all) {
            long rootId = resolveRootId(c, byId);
            if (rootId == c.getId()) continue;
            CommentResponse root = roots.get(rootId);
            if (root != null) {
                root.getReplies().add(toResponse(c, viewer, postOwnerId, rootId));
            }
        }

        return List.copyOf(roots.values());
    }

    // =====================================================================
    // HELPER
    // =====================================================================

    private User findUserOrNull(String email) {
        if (email == null || email.isBlank()) return null;
        return userRepository.findByEmail(email).orElse(null);
    }

    private User requireActiveUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy người dùng."));
        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalStateException("Tài khoản hiện không hoạt động.");
        }
        return user;
    }

    private PostComment requireComment(Long commentId) {
        return postCommentRepository.findById(commentId)
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy bình luận."));
    }

    private String validateContent(String raw) {
        String content = raw == null ? "" : raw.trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Nội dung bình luận không được để trống.");
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                    "Bình luận tối đa " + MAX_CONTENT_LENGTH + " ký tự.");
        }
        return content;
    }

    /**
     * Kiểm tra bình luận cha có thật và thuộc CÙNG bài (trước đây gửi parentId
     * của bài khác vẫn được lưu). Nếu cha lại là một trả lời thì đổi sang gốc.
     */
    private Long resolveParentId(Long requestedParentId, Post post) {
        if (requestedParentId == null) {
            return null;
        }
        PostComment parent = postCommentRepository.findById(requestedParentId)
                .orElseThrow(() -> new IllegalArgumentException("Bình luận được trả lời không tồn tại."));
        if (!parent.getPost().getId().equals(post.getId())) {
            throw new IllegalArgumentException("Bình luận được trả lời không thuộc bài viết này.");
        }

        // Đi ngược lên bình luận gốc (dữ liệu cũ có thể đã lồng nhiều cấp).
        PostComment root = parent;
        for (int depth = 0; depth < 50 && root.getParentId() != null; depth++) {
            Long upId = root.getParentId();
            root = postCommentRepository.findById(upId).orElse(null);
            if (root == null) {
                return upId; // cha đã bị xóa -> giữ id cuối cùng còn biết
            }
        }
        return root.getId();
    }

    /** Đi ngược lên cha để tìm bình luận gốc (có chặn vòng lặp). */
    private long resolveRootId(PostComment comment, Map<Long, PostComment> byId) {
        PostComment current = comment;
        for (int depth = 0; depth < 50; depth++) {
            Long parentId = current.getParentId();
            if (parentId == null) return current.getId();
            PostComment parent = byId.get(parentId);
            if (parent == null) return current.getId(); // cha không còn -> coi như gốc
            current = parent;
        }
        return current.getId();
    }

    private CommentResponse toResponse(PostComment comment, User viewer, Long postOwnerId, Long displayParentId) {
        CommentResponse dto = new CommentResponse();
        dto.setId(comment.getId());
        dto.setContent(comment.getContent());
        dto.setParentId(displayParentId);
        dto.setCreatedAt(comment.getCreatedAt());

        User author = comment.getUser();
        dto.setAuthorName(author.getFullName());
        dto.setAuthorUsername(author.getUsername());
        dto.setAuthorAvatar(author.getAvatar());

        boolean mine = viewer != null && viewer.getId().equals(author.getId());
        dto.setMyComment(mine);
        dto.setCanDelete(mine || (viewer != null && viewer.getId().equals(postOwnerId)));
        dto.setEdited(isEdited(comment));
        return dto;
    }

    /** updated_at lệch created_at quá 2 giây -> coi là đã sửa. */
    private boolean isEdited(PostComment comment) {
        LocalDateTime created = comment.getCreatedAt();
        LocalDateTime updated = comment.getUpdatedAt();
        return created != null && updated != null
                && Duration.between(created, updated).getSeconds() >= 2;
    }
}