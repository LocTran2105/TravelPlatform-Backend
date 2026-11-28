package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.Trip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface TripRepository extends JpaRepository<Trip, Long> {

    // Lấy toàn bộ chuyến đi của một người dùng cụ thể
    List<Trip> findByUserId(Long userId);

    // Dành cho Mạng xã hội: Lấy các chuyến đi được bật Public VÀ đã được Admin duyệt (APPROVED)
    List<Trip> findByIsPublicTrueAndPublicStatus(String publicStatus);

    @Query("""
        SELECT DISTINCT t FROM Trip t
        LEFT JOIN t.members m
        WHERE t.user.id = :userId
           OR (m.user.id = :userId AND m.status = 'ACCEPTED')
        """)
    List<Trip> findAllTripsOfUser(@Param("userId") Long userId);

    List<Trip> findByUser_IdOrderByIdDesc(Long userId);

    // ---- Lịch trình gắn trong bài viết (truy vấn GỘP cho cả trang bảng tin) ----

    /** Các chuyến đi kèm chủ chuyến đi, một câu SQL cho cả trang bài viết. */
    @Query("SELECT t FROM Trip t JOIN FETCH t.user WHERE t.id IN :ids")
    List<Trip> findAllWithOwnerByIdIn(@Param("ids") Collection<Long> ids);

    /** [postId, số lượt sao chép lịch trình từ bài đó] */
    @Query("""
        SELECT t.clonedFromPostId, COUNT(t) FROM Trip t
        WHERE t.clonedFromPostId IN :postIds
        GROUP BY t.clonedFromPostId
        """)
    List<Object[]> countCopiesByPostIds(@Param("postIds") Collection<Long> postIds);

    /** Số NGƯỜI (không tính tác giả bài) đã sao chép lịch trình trong một bài - để gộp thông báo. */
    @Query("""
        SELECT COUNT(DISTINCT t.user.id) FROM Trip t
        WHERE t.clonedFromPostId = :postId AND t.user.id <> :authorId
        """)
    long countDistinctCopiersOfPost(@Param("postId") Long postId, @Param("authorId") Long authorId);

    /** Số NGƯỜI (không tính chủ chuyến đi) đã sao chép một chuyến đi - dùng để gộp thông báo. */
    @Query("""
        SELECT COUNT(DISTINCT t.user.id) FROM Trip t
        WHERE t.clonedFromTripId = :tripId AND t.user.id <> :ownerId
        """)
    long countDistinctCopiers(@Param("tripId") Long tripId, @Param("ownerId") Long ownerId);
}