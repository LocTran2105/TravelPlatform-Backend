package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.TripReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TripReviewRepository extends JpaRepository<TripReview, Long> {
    // Lấy tất cả đánh giá của một chuyến đi
    List<TripReview> findByTripId(Long tripId);

    // Kiểm tra xem user này đã đánh giá chuyến đi này chưa (để chặn spam)
    Optional<TripReview> findByTripIdAndUserId(Long tripId, Long userId);
}