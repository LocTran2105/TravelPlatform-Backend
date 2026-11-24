package hcmute.edu.vn.backend.service;

import hcmute.edu.vn.backend.entity.Post;
import hcmute.edu.vn.backend.entity.User;
import hcmute.edu.vn.backend.repository.FollowRepository;
import hcmute.edu.vn.backend.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;

/**
 * Một nơi duy nhất trả lời câu hỏi "người này có được xem bài này không?".
 * Dùng cho bình luận và thả tim, để không ai đọc/viết bình luận trên bài
 * PRIVATE, bài đã xóa mềm hoặc bài chỉ dành cho người theo dõi / bạn bè.
 *
 * Quy tắc phải KHỚP với PostRepository.findFeedForViewer.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostAccessService {

    private final PostRepository postRepository;
    private final FollowRepository followRepository;

    /** viewer = null nghĩa là khách chưa đăng nhập. */
    public boolean canView(Post post, User viewer) {
        if (post == null || !"PUBLISHED".equals(post.getStatus())) {
            return false;
        }

        User author = post.getUser();
        if (viewer != null && viewer.getId().equals(author.getId())) {
            return true; // chủ bài luôn xem được bài còn tồn tại của mình
        }

        if (!"APPROVED".equals(post.getModerationStatus())) {
            return false;
        }

        String visibility = post.getVisibility();
        if ("PUBLIC".equals(visibility)) {
            return true;
        }
        if (viewer == null) {
            return false;
        }

        boolean viewerFollowsAuthor = followRepository.existsByFollowerAndFollowed(viewer, author);
        if ("FOLLOWERS".equals(visibility)) {
            return viewerFollowsAuthor;
        }
        if ("FRIENDS".equals(visibility)) {
            return viewerFollowsAuthor
                    && followRepository.existsByFollowerAndFollowed(author, viewer);
        }
        return false; // PRIVATE hoặc giá trị lạ
    }

    /**
     * Trả về bài nếu xem được, ngược lại ném NoSuchElementException (404).
     * Cố ý dùng 404 chứ không phải 403 để không lộ bài riêng tư có tồn tại.
     */
    public Post requireViewablePost(Long postId, User viewer) {
        return postRepository.findById(postId)
                .filter(post -> canView(post, viewer))
                .orElseThrow(() -> new NoSuchElementException("Không tìm thấy bài viết."));
    }
}