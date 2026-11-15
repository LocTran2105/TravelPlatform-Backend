package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Post;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PostRepository extends JpaRepository<Post, Long> {

    // ---- Giữ nguyên các hàm cũ (Admin / kiểm duyệt dùng) ----

    List<Post> findByModerationStatusOrderByCreatedAtDesc(String moderationStatus);

    Page<Post> findByModerationStatusOrderByCreatedAtDesc(String moderationStatus, Pageable pageable);

    Optional<Post> findByIdAndModerationStatus(Long id, String moderationStatus);

    long countByModerationStatus(String moderationStatus);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p
            FROM Post p
            JOIN FETCH p.user
            WHERE p.id = :postId
            """)
    Optional<Post> findByIdForModeration(@Param("postId") Long postId);

    // ---- MỚI ----

    /** Tìm bài chưa bị xóa mềm (dùng cho sửa / xóa). */
    Optional<Post> findByIdAndStatus(Long id, String status);

    /**
     * Bảng tin cộng đồng: chỉ bài PUBLISHED + APPROVED và CHỈ những bài
     * mà người xem được phép thấy theo visibility.
     *
     *  - PUBLIC     : ai cũng thấy (kể cả khách)
     *  - FOLLOWERS  : người xem đang theo dõi tác giả
     *  - FRIENDS    : hai bên theo dõi lẫn nhau
     *  - PRIVATE    : chỉ tác giả (điều kiện u.id = :viewerId)
     *
     * Khách chưa đăng nhập truyền viewerId = -1 (không trùng id nào).
     * Lưu ý: tên entity Follow và các field follower / followed phải khớp
     * với entity của bạn (FollowRepository.existsByFollowerAndFollowed
     * cho thấy field là follower, followed).
     */
    @Query("""
            SELECT p
            FROM Post p
            JOIN FETCH p.user u
            WHERE p.status = 'PUBLISHED'
              AND p.moderationStatus = 'APPROVED'
              AND (
                    p.visibility = 'PUBLIC'
                 OR u.id = :viewerId
                 OR (p.visibility = 'FOLLOWERS' AND EXISTS (
                        SELECT 1 FROM Follow f
                        WHERE f.follower.id = :viewerId AND f.followed.id = u.id))
                 OR (p.visibility = 'FRIENDS'
                     AND EXISTS (
                        SELECT 1 FROM Follow f1
                        WHERE f1.follower.id = :viewerId AND f1.followed.id = u.id)
                     AND EXISTS (
                        SELECT 1 FROM Follow f2
                        WHERE f2.follower.id = u.id AND f2.followed.id = :viewerId))
              )
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Post> findFeedForViewer(@Param("viewerId") Long viewerId);

    // Bài của một người dựa vào quyền hiển thị (giữ nguyên logic cũ)
    @Query("""
            SELECT p FROM Post p
            JOIN p.user u
            WHERE u.username = :username
            AND p.status = 'PUBLISHED'
            AND (
                (:isOwner = true AND p.moderationStatus IN ('PENDING', 'APPROVED', 'REJECTED'))
                OR
                (:isOwner = false AND p.moderationStatus = 'APPROVED' AND (
                    (p.visibility = 'PUBLIC') OR
                    (p.visibility = 'FOLLOWERS' AND :isFollower = true) OR
                    (p.visibility = 'FRIENDS' AND :isFriend = true)
                ))
            )
            ORDER BY p.createdAt DESC, p.id DESC
            """)
    List<Post> findUserPostsWithVisibility(
            @Param("username") String username,
            @Param("isOwner") boolean isOwner,
            @Param("isFollower") boolean isFollower,
            @Param("isFriend") boolean isFriend
    );
}