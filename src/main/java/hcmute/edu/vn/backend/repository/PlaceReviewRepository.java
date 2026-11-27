package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PlaceReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PlaceReviewRepository extends JpaRepository<PlaceReview, Long> {
    // Chỉ trả đánh giá đang hiển thị: đánh giá bị kiểm duyệt viên ẩn (HIDDEN) không lọt ra danh sách.
    // Giữ nguyên tên hàm để mọi nơi đang gọi hàm này tự được lọc.
    @Query("""
            SELECT r
            FROM PlaceReview r
            WHERE r.locationName = :locationName AND r.status = 'PUBLISHED'
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    List<PlaceReview> findByLocationNameOrderByCreatedAtDesc(@Param("locationName") String locationName);

    // 1 User chỉ được đánh giá 1 lần tại 1 địa điểm. KHÔNG lọc status: cần thấy cả đánh giá đã bị ẩn để chặn đăng lại.
    Optional<PlaceReview> findByLocationNameAndUserId(String locationName, Long userId);

    /**
     * Điểm trung bình và số đánh giá cộng đồng theo tên địa điểm (dùng để xếp hạng gợi ý AI).
     * Mỗi phần tử: [locationName (String), avgRating (Double), reviewCount (Long)].
     */
    @Query("""
            SELECT r.locationName, AVG(r.rating), COUNT(r)
            FROM PlaceReview r
            WHERE r.locationName IN :names AND r.rating IS NOT NULL AND r.status = 'PUBLISHED'
            GROUP BY r.locationName
            """)
    List<Object[]> aggregateRatingsByLocationNames(@Param("names") Collection<String> names);

    /**
     * Thống kê đánh giá theo tên địa điểm cho thẻ xem nhanh trên bản đồ.
     * Mỗi phần tử: [locationName, avgRating (Double), total (Long), recent từ :since (Long)].
     */
    @Query("""
            SELECT r.locationName, AVG(r.rating), COUNT(r),
                   SUM(CASE WHEN r.createdAt >= :since THEN 1 ELSE 0 END)
            FROM PlaceReview r
            WHERE r.locationName IN :names AND r.status = 'PUBLISHED'
            GROUP BY r.locationName
            """)
    List<Object[]> activityByLocationNames(@Param("names") Collection<String> names,
                                           @Param("since") LocalDateTime since);
}