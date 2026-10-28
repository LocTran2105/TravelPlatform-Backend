package hcmute.edu.vn.backend.repository;

import hcmute.edu.vn.backend.entity.PlaceReview;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PlaceReviewRepository extends JpaRepository<PlaceReview, Long> {
    List<PlaceReview> findByLocationNameOrderByCreatedAtDesc(String locationName);

    // Thêm dòng này để phục vụ logic: 1 User chỉ được đánh giá 1 lần tại 1 địa điểm
    Optional<PlaceReview> findByLocationNameAndUserId(String locationName, Long userId);
}