package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Follow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FollowRepository extends JpaRepository<Follow, Long> {
    Optional<Follow> findByFollowerIdAndFollowedId(Long followerId, Long followedId);

    long countByFollowedId(Long followedId); // Đếm số người theo dõi (Followers)
    long countByFollowerId(Long followerId); // Đếm số người đang theo dõi (Following)
}