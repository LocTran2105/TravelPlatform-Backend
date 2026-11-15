package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Follow;
import hcmute.edu.vn.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FollowRepository extends JpaRepository<Follow, Long> {

    Optional<Follow> findByFollowerIdAndFollowedId(Long followerId, Long followedId);

    boolean existsByFollowerIdAndFollowedId(Long followerId, Long followedId);

    // Kiểm tra xem User A đã follow User B chưa
    boolean existsByFollowerAndFollowed(User follower, User followed);

    // Tìm bản ghi follow cụ thể để xóa (Unfollow)
    Optional<Follow> findByFollowerAndFollowed(User follower, User followed);

    // Những người mà User đang theo dõi (Following). Nạp luôn user được theo dõi để tránh truy vấn lặp.
    @EntityGraph(attributePaths = "followed")
    Page<Follow> findByFollower(User follower, Pageable pageable);

    // Những người theo dõi User này (Followers). Nạp luôn user theo dõi để tránh truy vấn lặp.
    @EntityGraph(attributePaths = "follower")
    Page<Follow> findByFollowed(User followed, Pageable pageable);

    long countByFollowedId(Long followedId); // Đếm số người theo dõi (Followers)
    long countByFollowerId(Long followerId); // Đếm số người đang theo dõi (Following)

    // Trong danh sách ids: những người mà viewer đang theo dõi
    @Query("select f.followed.id from Follow f where f.follower.id = :viewerId and f.followed.id in :ids")
    List<Long> findFollowedIdsByFollowerAndIdIn(@Param("viewerId") Long viewerId,
                                                @Param("ids") Collection<Long> ids);

    // Trong danh sách ids: những người đang theo dõi viewer
    @Query("select f.follower.id from Follow f where f.followed.id = :viewerId and f.follower.id in :ids")
    List<Long> findFollowerIdsByFollowedAndIdIn(@Param("viewerId") Long viewerId,
                                                @Param("ids") Collection<Long> ids);
}