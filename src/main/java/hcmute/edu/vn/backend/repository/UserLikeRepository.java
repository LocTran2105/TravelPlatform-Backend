package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.UserLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserLikeRepository extends JpaRepository<UserLike, Long> {
    // Tìm lượt like để kiểm tra xem user đã like chưa (phục vụ chức năng Unlike)
    Optional<UserLike> findByUserIdAndTargetTypeAndTargetId(Long userId, String targetType, Long targetId);

    // Đếm tổng số lượt like của một bài viết/địa điểm
    long countByTargetTypeAndTargetId(String targetType, Long targetId);
}