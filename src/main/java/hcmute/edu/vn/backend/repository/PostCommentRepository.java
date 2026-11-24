package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PostComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PostCommentRepository extends JpaRepository<PostComment, Long> {

    // Lấy tất cả bình luận của 1 Post, sắp xếp cũ nhất lên đầu
    List<PostComment> findByPostIdOrderByCreatedAtAsc(Long postId);

    // MỚI: như trên nhưng nạp luôn tác giả trong 1 câu SQL (tránh N+1:
    // trước đây mỗi bình luận kéo thêm 1 câu SELECT user).
    @Query("""
            SELECT c
            FROM PostComment c
            JOIN FETCH c.user
            WHERE c.post.id = :postId
            ORDER BY c.createdAt ASC, c.id ASC
            """)
    List<PostComment> findAllByPostIdWithUser(@Param("postId") Long postId);

    long countByPostId(Long postId);
}