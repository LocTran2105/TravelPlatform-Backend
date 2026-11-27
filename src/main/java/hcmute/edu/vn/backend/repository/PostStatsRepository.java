package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Image;
import hcmute.edu.vn.backend.entity.Post;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * Truy vấn GỘP cho cả trang bài viết: thay vì mỗi bài chạy 3 câu SQL
 * (đếm tim, kiểm tra đã tim, lấy ảnh) thì cả danh sách chỉ chạy 4 câu.
 *
 * Gắn với entity Post cho đúng cú pháp Spring Data; các câu @Query bên dưới
 * truy vấn cả UserLike, PostComment, Image.
 */
@org.springframework.stereotype.Repository
public interface PostStatsRepository extends Repository<Post, Long> {

    /** [postId, số tim] */
    @Query("""
            SELECT l.targetId, COUNT(l)
            FROM UserLike l
            WHERE l.targetType = 'POST' AND l.targetId IN :postIds
            GROUP BY l.targetId
            """)
    List<Object[]> countLikesByPostIds(@Param("postIds") Collection<Long> postIds);

    /** Trong danh sách bài, những bài mà user đã thả tim. */
    @Query("""
            SELECT l.targetId
            FROM UserLike l
            WHERE l.targetType = 'POST' AND l.user.id = :userId AND l.targetId IN :postIds
            """)
    List<Long> findLikedPostIds(@Param("userId") Long userId,
                                @Param("postIds") Collection<Long> postIds);

    /**
     * [postId, số bình luận] (tính cả trả lời).
     * Không đếm bình luận bị ẩn, và không đếm trả lời nằm dưới bình luận gốc bị ẩn
     * (khớp với danh sách CommentService trả về).
     */
    @Query("""
            SELECT c.post.id, COUNT(c)
            FROM PostComment c
            WHERE c.post.id IN :postIds
              AND c.status = 'PUBLISHED'
              AND (c.parentId IS NULL OR NOT EXISTS (
                    SELECT 1 FROM PostComment p
                    WHERE p.id = c.parentId AND p.status = 'HIDDEN'))
            GROUP BY c.post.id
            """)
    List<Object[]> countCommentsByPostIds(@Param("postIds") Collection<Long> postIds);

    /** Ảnh/video của nhiều bài, theo thứ tự upload (id tăng dần). */
    @Query("""
            SELECT i
            FROM Image i
            WHERE i.entityType = 'POST' AND i.entityId IN :postIds
            ORDER BY i.entityId ASC, i.id ASC
            """)
    List<Image> findMediaByPostIds(@Param("postIds") Collection<Long> postIds);

    /** Trong danh sách tác giả, những người MÀ viewer đang theo dõi. */
    @Query("""
            SELECT f.followed.id
            FROM Follow f
            WHERE f.follower.id = :viewerId AND f.followed.id IN :authorIds
            """)
    List<Long> findFollowedAuthorIds(@Param("viewerId") Long viewerId,
                                     @Param("authorIds") Collection<Long> authorIds);

    /** Trong danh sách tác giả, những người ĐANG theo dõi viewer. */
    @Query("""
            SELECT f.follower.id
            FROM Follow f
            WHERE f.followed.id = :viewerId AND f.follower.id IN :authorIds
            """)
    List<Long> findFollowerAuthorIds(@Param("viewerId") Long viewerId,
                                     @Param("authorIds") Collection<Long> authorIds);
}
