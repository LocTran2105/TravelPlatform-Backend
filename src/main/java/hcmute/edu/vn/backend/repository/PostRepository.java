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

    // Danh sách bài đăng đã được duyệt, dùng cho trang cộng đồng.
    List<Post> findByModerationStatusOrderByCreatedAtDesc(
            String moderationStatus
    );

    // Danh sách bài đăng cho Admin, có phân trang.
    Page<Post> findByModerationStatusOrderByCreatedAtDesc(
            String moderationStatus,
            Pageable pageable
    );

    // Tìm bài đăng theo ID và trạng thái.
    Optional<Post> findByIdAndModerationStatus(
            Long id,
            String moderationStatus
    );

    // Đếm số bài đăng theo trạng thái kiểm duyệt.
    long countByModerationStatus(String moderationStatus);

    /**
     * Khóa bài đăng trong khi Admin duyệt.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT p
            FROM Post p
            JOIN FETCH p.user
            WHERE p.id = :postId
            """)
    Optional<Post> findByIdForModeration(
            @Param("postId") Long postId
    );
}